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

/** Read-only HTTP endpoint on the loopback interface: the UI page and a JSON snapshot of every swarm. */
public final class UiServer implements AutoCloseable {
    public static final int DEFAULT_PORT = 8088;
    private static final String PAGE = "/ui/swarm-observatory.html";
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

    /** Header a page must send with every action; cross-site requests cannot add it without a preflight, which is refused. */
    public static final String ACTION_HEADER = "X-Swarm-UI";
    private static final Pattern ACTION_PATH = Pattern.compile("/api/servers/([A-Za-z][A-Za-z0-9._-]*-Server-\\d{3})/(stop|leave|restart)");

    private static final Pattern ACTIVITY_PATH = Pattern.compile("/api/servers/([A-Za-z][A-Za-z0-9._-]*-Server-\\d{3})/activity");
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

    private void action(HttpExchange exchange, SwarmManager manager) throws IOException {
        var matcher = ACTION_PATH.matcher(exchange.getRequestURI().getPath());
        if (!matcher.matches()) { reply(exchange, 404, "application/json", error("Not found")); return; }
        String serverId = matcher.group(1), what = matcher.group(2);
        try {
            switch (what) {
                case "stop" -> manager.stopServer(serverId);
                case "leave" -> manager.leaveServer(serverId);
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

    private void activity(HttpExchange exchange, SwarmManager manager) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (ACTION_PATH.matcher(path).matches()) { reply(exchange, 405, "text/plain", "Only POST is supported here"); return; }
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
