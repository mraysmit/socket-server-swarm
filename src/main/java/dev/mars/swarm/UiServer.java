package dev.mars.swarm;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.mars.protocol.Json;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** HTTP endpoint on the loopback interface: the UI page, a JSON snapshot of every swarm, and the actions that control them. */
public final class UiServer implements AutoCloseable {
    public static final int DEFAULT_PORT = 8088;
    private static final String PAGE = "/ui/swarm-observatory.html";
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

    /** Header a page must send with every action; cross-site requests cannot add it without a preflight, which is refused. */
    public static final String ACTION_HEADER = "X-Swarm-UI";
    private static final Pattern ACTION_PATH = Pattern.compile("/api/servers/([A-Za-z][A-Za-z0-9._-]*-Server-\\d{3})/(stop|leave|restart|remove|freeze|thaw)");

    private static final Pattern COMMAND_PATH = Pattern.compile("/api/servers/([A-Za-z][A-Za-z0-9._-]*-Server-\\d{3})/command");
    private static final Pattern MEMBERS_PATH = Pattern.compile("/api/servers/([A-Za-z][A-Za-z0-9._-]*-Server-\\d{3})/members");
    private static final Pattern ACTIVITY_PATH = Pattern.compile("/api/servers/([A-Za-z][A-Za-z0-9._-]*-Server-\\d{3})/activity");
    private static final Pattern SWARM_ACTION_PATH = Pattern.compile("/api/swarms/([A-Za-z][A-Za-z0-9._-]*)/(stop|send|test|servers)");
    private static final int MAX_BODY = 8192;
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    private final SocketActivity activity;

    /** Pass port 0 to let the operating system choose, as the tests do. */
    public UiServer(SwarmManager manager, int port) throws IOException { this(manager, port, null); }

    /** With a recorder, {@code GET /api/servers/<id>/activity} serves each server's socket activity. */
    public UiServer(SwarmManager manager, int port, SocketActivity activity) throws IOException {
        this.activity = activity;
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        server.createContext("/", exchange -> handle(exchange, "GET", () -> page(exchange)));
        server.createContext("/api/snapshot", exchange -> handle(exchange, "GET", () -> snapshot(exchange, manager)));
        server.createContext("/api/servers/", exchange -> {
            if (exchange.getRequestMethod().equals("GET")) { handle(exchange, "GET", () -> activity(exchange, manager)); }
            else { handle(exchange, "POST", () -> action(exchange, manager)); }
        });
        server.createContext("/api/swarms", exchange -> handle(exchange, "POST", () -> swarmAction(exchange, manager)));
        server.createContext("/api/stop-all", exchange -> handle(exchange, "POST", () -> stopAll(exchange, manager)));
        server.setExecutor(executor);
        server.start();
    }

    public int port() { return server.getAddress().getPort(); }

    @Override public void close() {
        server.stop(1);
        executor.shutdown();
    }

    private interface Route { void run() throws IOException; }

    private void handle(HttpExchange exchange, String method, Route route) throws IOException {
        try (exchange) {
            if (!localHost(exchange.getRequestHeaders().getFirst("Host"))) { reply(exchange, 403, "text/plain", "Forbidden"); return; }
            if (!exchange.getRequestMethod().equals(method)) { reply(exchange, 405, "text/plain", "Only " + method + " is supported here"); return; }
            if (method.equals("POST") && !sameOriginAction(exchange)) { reply(exchange, 403, "text/plain", "Forbidden"); return; }
            route.run();
        } catch (RuntimeException e) {
            reply(exchange, 500, "text/plain", "Internal error");
        }
    }

    /** An action must come from a page this server served: the Origin names this server and the custom header is present. */
    private boolean sameOriginAction(HttpExchange exchange) {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin == null || exchange.getRequestHeaders().getFirst(ACTION_HEADER) == null) { return false; }
        int port = port();
        return Set.of("http://localhost:" + port, "http://127.0.0.1:" + port, "http://[::1]:" + port).contains(origin);
    }

    /** {@code POST /api/servers/<id>/command} with {"text": "..."}: sends it to that server and returns everything that came back. */
    private void command(HttpExchange exchange, SwarmManager manager, String serverId) throws IOException {
        try {
            var result = manager.command(serverId, text(body(exchange), "text"));
            reply(exchange, 200, "application/json; charset=utf-8", Json.write(result));
        } catch (IllegalArgumentException e) {
            reply(exchange, e.getMessage() != null && e.getMessage().startsWith("Unknown server") ? 404 : 400, "application/json", error(e.getMessage()));
        } catch (IllegalStateException e) {
            reply(exchange, 409, "application/json", error(e.getMessage()));
        }
    }

    private void action(HttpExchange exchange, SwarmManager manager) throws IOException {
        var commandMatcher = COMMAND_PATH.matcher(exchange.getRequestURI().getPath());
        if (commandMatcher.matches()) { command(exchange, manager, commandMatcher.group(1)); return; }
        var matcher = ACTION_PATH.matcher(exchange.getRequestURI().getPath());
        if (!matcher.matches()) { reply(exchange, 404, "application/json", error("Not found")); return; }
        String serverId = matcher.group(1), what = matcher.group(2);
        try {
            switch (what) {
                case "stop" -> manager.stopServer(serverId);
                case "leave" -> manager.leaveServer(serverId);
                case "freeze" -> manager.freezeServer(serverId);
                case "thaw" -> manager.thawServer(serverId);
                case "remove" -> {
                    manager.removeServer(serverId);
                    if (activity != null) { activity.forget(serverId); }
                }
                default -> manager.restartServer(serverId);
            }
            reply(exchange, 200, "application/json", "{\"ok\":true}");
        } catch (IllegalArgumentException e) {
            reply(exchange, 404, "application/json", error(e.getMessage()));
        } catch (IllegalStateException e) {
            reply(exchange, 409, "application/json", error(e.getMessage()));
        } catch (IOException e) {
            reply(exchange, 500, "application/json", error(e.getMessage()));
        }
    }

    /** Starts a swarm ({@code POST /api/swarms}), or stops, sends to or load-tests one ({@code POST /api/swarms/<name>/<action>}). */
    private void swarmAction(HttpExchange exchange, SwarmManager manager) throws IOException {
        String path = exchange.getRequestURI().getPath();
        try {
            var body = body(exchange);
            if (path.equals("/api/swarms")) {
                var endpoints = manager.start(text(body, "name"), integer(body, "servers", 0));
                reply(exchange, 200, "application/json", Json.write(java.util.Map.of("ok", true, "servers", endpoints.stream().map(ServerEndpoint::serverId).toList())));
                return;
            }
            var matcher = SWARM_ACTION_PATH.matcher(path);
            if (!matcher.matches()) { reply(exchange, 404, "application/json", error("Not found")); return; }
            String name = matcher.group(1);
            switch (matcher.group(2)) {
                case "stop" -> {
                    manager.stop(name);
                    reply(exchange, 200, "application/json", "{\"ok\":true}");
                }
                case "servers" -> {
                    var added = manager.addServers(name, integer(body, "count", 1));
                    reply(exchange, 200, "application/json", Json.write(java.util.Map.of("ok", true, "servers", added.stream().map(ServerEndpoint::serverId).toList())));
                }
                case "send" -> {
                    var replies = manager.send(name, text(body, "text")).stream().map(r -> {
                        var row = new java.util.LinkedHashMap<String, Object>();
                        row.put("serverId", r.serverId());
                        row.put("status", r.status());
                        row.put("result", r.result());
                        if (r.error() != null) { row.put("error", r.error().message()); }
                        return row;
                    }).toList();
                    reply(exchange, 200, "application/json", Json.write(java.util.Map.of("ok", true, "replies", replies)));
                }
                default -> {
                    var result = manager.test(name, integer(body, "requests", 1000), integer(body, "concurrency", 100), done -> { });
                    var row = new java.util.LinkedHashMap<String, Object>();
                    row.put("ok", result.successful());
                    row.put("requests", result.requests());
                    row.put("passed", result.passed());
                    row.put("failed", result.failed());
                    row.put("elapsedMillis", result.elapsedMillis());
                    row.put("failures", result.failures());
                    reply(exchange, 200, "application/json", Json.write(row));
                }
            }
        } catch (IllegalArgumentException e) {
            reply(exchange, e.getMessage() != null && e.getMessage().startsWith("Unknown swarm") ? 404 : 400, "application/json", error(e.getMessage()));
        } catch (IllegalStateException e) {
            reply(exchange, 409, "application/json", error(e.getMessage()));
        } catch (IOException e) {
            reply(exchange, 500, "application/json", error(e.getMessage()));
        }
    }

    private void stopAll(HttpExchange exchange, SwarmManager manager) throws IOException {
        try {
            manager.stopAll();
            reply(exchange, 200, "application/json", "{\"ok\":true}");
        } catch (IllegalStateException e) {
            reply(exchange, 409, "application/json", error(e.getMessage()));
        } catch (IOException e) {
            reply(exchange, 500, "application/json", error(e.getMessage()));
        }
    }

    /** The request body as JSON; no body means an empty object. */
    private static com.fasterxml.jackson.databind.JsonNode body(HttpExchange exchange) throws IOException {
        byte[] bytes;
        try (InputStream in = exchange.getRequestBody()) { bytes = in.readNBytes(MAX_BODY + 1); }
        if (bytes.length > MAX_BODY) { throw new IllegalArgumentException("Request body is too large"); }
        String text = new String(bytes, StandardCharsets.UTF_8).trim();
        if (text.isEmpty()) { return Json.read("{}"); }
        try {
            var node = Json.read(text);
            if (!node.isObject()) { throw new IllegalArgumentException("Request body must be a JSON object"); }
            return node;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("Request body is not valid JSON");
        }
    }

    private static String text(com.fasterxml.jackson.databind.JsonNode body, String field) {
        var node = body.get(field);
        if (node == null || !node.isTextual() || node.asText().isBlank()) { throw new IllegalArgumentException("Missing text field: " + field); }
        return node.asText();
    }

    private static int integer(com.fasterxml.jackson.databind.JsonNode body, String field, int fallback) {
        var node = body.get(field);
        if (node == null || node.isNull()) {
            if (fallback > 0) { return fallback; }
            throw new IllegalArgumentException("Missing number field: " + field);
        }
        if (!node.isIntegralNumber() || !node.canConvertToInt()) { throw new IllegalArgumentException("Field must be a whole number: " + field); }
        return node.asInt();
    }

    private void activity(HttpExchange exchange, SwarmManager manager) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (ACTION_PATH.matcher(path).matches() || COMMAND_PATH.matcher(path).matches()) { reply(exchange, 405, "text/plain", "Only POST is supported here"); return; }
        var members = MEMBERS_PATH.matcher(path);
        if (members.matches()) { fullMembers(exchange, manager, members.group(1)); return; }
        var matcher = ACTIVITY_PATH.matcher(path);
        if (!matcher.matches()) { reply(exchange, 404, "application/json", error("Not found")); return; }
        String serverId = matcher.group(1);
        boolean known = manager.list().values().stream().anyMatch(list -> list.stream().anyMatch(e -> e.serverId().equals(serverId)));
        if (!known) { reply(exchange, 404, "application/json", error("Unknown server: " + serverId)); return; }
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("serverId", serverId);
        body.put("available", activity != null);
        if (activity != null) {
            var view = activity.view(serverId, afterParameter(exchange.getRequestURI().getRawQuery()), 200);
            if (view == null) { view = new SocketActivity.View(0, 1, new SocketActivity.Counters(0, 0, 0, 0, 0, java.util.Map.of()), java.util.List.of()); }
            body.put("lastSeq", view.lastSeq());
            body.put("firstSeq", view.firstSeq());
            body.put("counters", view.counters());
            body.put("events", view.events());
        }
        reply(exchange, 200, "application/json; charset=utf-8", Json.write(body));
    }

    /** One gossip server's complete member list; a swarm larger than one 32-member page needs this to be seen whole. */
    private void fullMembers(HttpExchange exchange, SwarmManager manager, String serverId) throws IOException {
        var endpoint = manager.list().values().stream().flatMap(java.util.List::stream)
                .filter(e -> e.serverId().equals(serverId)).findFirst();
        if (endpoint.isEmpty()) { reply(exchange, 404, "application/json", error("Unknown server: " + serverId)); return; }
        try {
            reply(exchange, 200, "application/json; charset=utf-8", Json.write(SwarmSnapshot.fullMembers(manager, endpoint.get())));
        } catch (IOException | RuntimeException e) {
            reply(exchange, 502, "application/json", error(e.getMessage()));
        }
    }

    private static long afterParameter(String query) {
        if (query != null) {
            for (String pair : query.split("&")) {
                if (pair.startsWith("after=") && pair.substring(6).matches("[0-9]{1,18}")) { return Long.parseLong(pair.substring(6)); }
            }
        }
        return 0;
    }

    private static String error(String message) throws IOException {
        return Json.write(java.util.Map.of("ok", false, "error", String.valueOf(message)));
    }

    /** Refuses requests whose Host header is not a loopback name, which blocks DNS-rebinding reads from other sites. */
    private static boolean localHost(String header) {
        if (header == null) { return false; }
        String host = header.toLowerCase(Locale.ROOT);
        int colon = host.lastIndexOf(':');
        if (colon > host.lastIndexOf(']')) { host = host.substring(0, colon); }
        return LOCAL_HOSTS.contains(host);
    }

    private void page(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (!path.equals("/") && !path.equals("/index.html")) { reply(exchange, 404, "text/plain", "Not found"); return; }
        try (InputStream in = UiServer.class.getResourceAsStream(PAGE)) {
            if (in == null) { reply(exchange, 404, "text/plain", "UI page is not packaged"); return; }
            reply(exchange, 200, "text/html; charset=utf-8", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private void snapshot(HttpExchange exchange, SwarmManager manager) throws IOException {
        if (!exchange.getRequestURI().getPath().equals("/api/snapshot")) { reply(exchange, 404, "text/plain", "Not found"); return; }
        reply(exchange, 200, "application/json; charset=utf-8", Json.write(SwarmSnapshot.take(manager)));
    }

    private static void reply(HttpExchange exchange, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        var headers = exchange.getResponseHeaders();
        headers.set("Content-Type", type);
        headers.set("Cache-Control", "no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
