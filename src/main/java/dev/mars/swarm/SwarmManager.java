package dev.mars.swarm;

import dev.mars.client.TextMessageClient;
import dev.mars.protocol.CommandReply;
import dev.mars.server.ServerInstance;
import dev.mars.server.ServerOptions;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

/** Owns named groups of real socket servers, all hosted in the harness JVM. */
public final class SwarmManager implements AutoCloseable {
    public static final int MAX_SERVERS_PER_SWARM = 100;
    private final Map<String, List<ServerInstance>> swarms = new LinkedHashMap<>();
    private final Path pluginsDir;
    private final ServerFactory factory;
    private boolean closed;

    public SwarmManager() { this(null); }
    public SwarmManager(Path pluginsDir) { this(pluginsDir, ServerInstance::start); }
    SwarmManager(Path pluginsDir, ServerFactory factory) {
        this.pluginsDir = pluginsDir;
        this.factory = factory;
    }

    public synchronized List<ServerEndpoint> start(String name, int count) throws IOException {
        ensureOpen();
        if (name == null || !name.matches("[A-Za-z][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException("Swarm name must start with a letter and use letters, digits, dots, underscores or hyphens");
        }
        if (count < 1 || count > MAX_SERVERS_PER_SWARM) {
            throw new IllegalArgumentException("Server count must be between 1 and " + MAX_SERVERS_PER_SWARM);
        }
        if (swarms.containsKey(name)) { throw new IllegalArgumentException("Swarm already exists: " + name); }
        var opened = new ConcurrentLinkedQueue<ServerInstance>();
        var ordered = new ArrayList<ServerInstance>();
        boolean committed = false;
        try {
            try (var starters = Executors.newVirtualThreadPerTaskExecutor()) {
                var futures = new ArrayList<Future<ServerInstance>>();
                for (int i = 1; i <= count; i++) {
                    String id = name + "-Server-" + String.format(java.util.Locale.ROOT, "%03d", i);
                    futures.add(starters.submit(() -> {
                        var server = factory.start(new ServerOptions(id, 0, 0, pluginsDir));
                        opened.add(server);
                        return server;
                    }));
                }
                IOException failure = null;
                for (var future : futures) {
                    try { ordered.add(future.get()); }
                    catch (ExecutionException e) {
                        var error = new IOException("Cannot start swarm " + name + ": " + e.getCause().getMessage(), e.getCause());
                        if (failure == null) { failure = error; } else { failure.addSuppressed(error); }
                    }
                }
                if (failure != null) { throw failure; }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                var error = new InterruptedIOException("Swarm startup interrupted: " + name);
                error.initCause(e);
                throw error;
            }
            swarms.put(name, List.copyOf(ordered));
            committed = true;
            return endpoints(ordered);
        } catch (IOException | RuntimeException e) {
            try { closeServers(new ArrayList<>(opened)); } catch (IOException cleanup) { e.addSuppressed(cleanup); }
            throw e;
        } finally {
            if (!committed) { swarms.remove(name); }
        }
    }

    public synchronized Map<String, List<ServerEndpoint>> list() {
        var snapshot = new LinkedHashMap<String, List<ServerEndpoint>>();
        swarms.forEach((name, servers) -> snapshot.put(name, endpoints(servers)));
        return Collections.unmodifiableMap(snapshot);
    }

    public List<ServerStatus> status(String name) {
        var statuses = new ArrayList<ServerStatus>();
        for (var endpoint : snapshot(name)) {
            try {
                String detail = adminStatus(endpoint);
                statuses.add(new ServerStatus(endpoint, detail.equals("Server status: ALIVE"), detail));
            } catch (IOException e) {
                statuses.add(new ServerStatus(endpoint, false, e.getMessage()));
            }
        }
        return List.copyOf(statuses);
    }

    public List<CommandReply> send(String name, String input) throws IOException {
        var replies = new ArrayList<CommandReply>();
        for (var endpoint : snapshot(name)) {
            replies.add(client(endpoint).executeInput(UUID.randomUUID(), input));
        }
        return List.copyOf(replies);
    }

    public LoadResult test(String name, int requests, int concurrency, IntConsumer progress) throws IOException {
        if (requests < 1 || requests > 100_000) { throw new IllegalArgumentException("Requests must be between 1 and 100000"); }
        if (concurrency < 1 || concurrency > 1000) { throw new IllegalArgumentException("Concurrency must be between 1 and 1000"); }
        java.util.Objects.requireNonNull(progress, "progress");
        var targets = snapshot(name);
        var permits = new Semaphore(concurrency);
        var verified = new AtomicInteger();
        var completed = new AtomicInteger();
        var failures = new ConcurrentLinkedQueue<String>();
        long began = System.nanoTime();
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = new ArrayList<Future<?>>();
            for (int i = 0; i < requests; i++) {
                int index = i;
                tasks.add(workers.submit(() -> {
                    boolean acquired = false;
                    try {
                        permits.acquire();
                        acquired = true;
                        var target = targets.get(index % targets.size());
                        UUID id = UUID.randomUUID();
                        var reply = client(target).execute(id, "math.add", Map.of("a", index, "b", 1));
                        if (!Integer.toString(index + 1).equals(reply.result().toString())) {
                            throw new IOException("Unexpected result from " + target.serverId() + ": " + reply.result());
                        }
                        verified.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        failures.add("Request " + index + " interrupted");
                    } catch (IOException | RuntimeException e) {
                        failures.add("Request " + index + ": " + e.getMessage());
                    } finally {
                        if (acquired) { permits.release(); }
                        synchronized (completed) {
                            int done = completed.incrementAndGet();
                            if (done % 100 == 0 || done == requests) { progress.accept(done); }
                        }
                    }
                }));
            }
            for (var task : tasks) {
                try { task.get(); }
                catch (ExecutionException e) { throw new IOException("Load task failed", e.getCause()); }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    var error = new InterruptedIOException("Swarm test interrupted");
                    error.initCause(e);
                    throw error;
                }
            }
        }
        return new LoadResult(name, requests, verified.get(), failures.size(),
                (System.nanoTime() - began) / 1_000_000, failures.stream().limit(10).toList());
    }

    public synchronized void stop(String name) throws IOException {
        ensureOpen();
        var servers = swarms.remove(name);
        if (servers == null) { throw new IllegalArgumentException("Unknown swarm: " + name); }
        closeServers(servers);
    }

    public synchronized void stopAll() throws IOException {
        var servers = swarms.values().stream().flatMap(List::stream).toList();
        swarms.clear();
        closeServers(servers);
    }

    @Override public synchronized void close() throws IOException {
        if (!closed) {
            closed = true;
            stopAll();
        }
    }

    private synchronized List<ServerEndpoint> snapshot(String name) {
        ensureOpen();
        var servers = swarms.get(name);
        if (servers == null) { throw new IllegalArgumentException("Unknown swarm: " + name); }
        return endpoints(servers);
    }

    private void ensureOpen() { if (closed) { throw new IllegalStateException("Swarm manager is closed"); } }
    private static List<ServerEndpoint> endpoints(List<ServerInstance> servers) {
        return servers.stream().map(server -> new ServerEndpoint(server.getServerId(), server.getPort(), server.getAdminPort())).toList();
    }
    private static TextMessageClient client(ServerEndpoint endpoint) {
        return new TextMessageClient("localhost", endpoint.port(), endpoint.serverId());
    }
    private static String adminStatus(ServerEndpoint endpoint) throws IOException {
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", endpoint.adminPort()), 3000);
            socket.setSoTimeout(3000);
            var output = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
            var input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            output.println("status");
            if (output.checkError()) { throw new IOException("Could not send status request"); }
            String status = input.readLine();
            if (status == null) { throw new IOException("Server closed without status reply"); }
            return status;
        }
    }
    private static void closeServers(List<ServerInstance> servers) throws IOException {
        IOException failure = null;
        for (var server : servers.reversed()) {
            try { server.close(); }
            catch (RuntimeException e) {
                var error = new IOException("Cannot stop " + server.getServerId(), e);
                if (failure == null) { failure = error; } else { failure.addSuppressed(error); }
            }
        }
        if (failure != null) { throw failure; }
    }
    @FunctionalInterface interface ServerFactory { ServerInstance start(ServerOptions options) throws IOException; }
}
