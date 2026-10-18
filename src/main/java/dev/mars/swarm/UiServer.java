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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Read-only HTTP endpoint on the loopback interface: the UI page and a JSON snapshot of every swarm. */
public final class UiServer implements AutoCloseable {
    public static final int DEFAULT_PORT = 8088;
    private static final String PAGE = "/ui/swarm-observatory.html";
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /** Pass port 0 to let the operating system choose, as the tests do. */
    public UiServer(SwarmManager manager, int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        server.createContext("/", exchange -> handle(exchange, () -> page(exchange)));
        server.createContext("/api/snapshot", exchange -> handle(exchange, () -> snapshot(exchange, manager)));
        server.setExecutor(executor);
        server.start();
    }

    public int port() { return server.getAddress().getPort(); }

    @Override public void close() {
        server.stop(1);
        executor.shutdown();
    }

    private interface Route { void run() throws IOException; }

    private void handle(HttpExchange exchange, Route route) throws IOException {
        try (exchange) {
            if (!localHost(exchange.getRequestHeaders().getFirst("Host"))) { reply(exchange, 403, "text/plain", "Forbidden"); return; }
            if (!exchange.getRequestMethod().equals("GET")) { reply(exchange, 405, "text/plain", "Only GET is supported"); return; }
            route.run();
        } catch (RuntimeException e) {
            reply(exchange, 500, "text/plain", "Internal error");
        }
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
