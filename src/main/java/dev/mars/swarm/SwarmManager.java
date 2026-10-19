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
    private static final java.util.regex.Pattern SERVER_ID = java.util.regex.Pattern.compile("(.+)-Server-(\\d{3})");
    private final Map<String, List<ServerInstance>> swarms = new LinkedHashMap<>();
    private final List<Path> pluginDirectories;
    private final ServerFactory factory;
    private final GossipSettings gossip;
    private GossipConfigFile gossipFile;
    private final ConnectionPool connections = new ConnectionPool();
    private volatile boolean closed;

    public SwarmManager() { this(List.of()); }
    public SwarmManager(Path pluginsDir) { this(pluginsDir == null ? List.of() : List.of(pluginsDir)); }
    public SwarmManager(List<Path> pluginDirectories) { this(pluginDirectories, ServerInstance::start); }
    /** With gossip settings, every swarm's servers get a generated gossip config; the plugin directories must hold the plugin. */
    public SwarmManager(List<Path> pluginDirectories, GossipSettings gossip) {
        this(pluginDirectories, gossip, ServerInstance::start);
    }
    SwarmManager(List<Path> pluginDirectories, ServerFactory factory) { this(pluginDirectories, null, factory); }
    SwarmManager(List<Path> pluginDirectories, GossipSettings gossip, ServerFactory factory) {
        this.pluginDirectories = List.copyOf(pluginDirectories);
        this.gossip = gossip;
        this.factory = factory;
    }

    public boolean gossipEnabled() { return gossip != null; }

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
            if (gossip != null) {
                if (gossipFile == null) { gossipFile = new GossipConfigFile(gossip); }
                gossipFile.declare(name, count);
            }
            try (var starters = Executors.newVirtualThreadPerTaskExecutor()) {
                var futures = new ArrayList<Future<ServerInstance>>();
                int next = 1;
                if (gossip != null) {
                    // The first server starts alone: the others need its actual port as their seed.
                    var first = startOne(name, 1, opened);
                    ordered.add(first);
                    gossipFile.seedFirstServer(name, count, first.getPort());
                    next = 2;
                }
                for (int i = next; i <= count; i++) {
                    int index = i;
                    futures.add(starters.submit(() -> startOne(name, index, opened)));
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
            synchronized (swarms) { swarms.put(name, List.copyOf(ordered)); }
            committed = true;
            return endpoints(ordered);
        } catch (IOException | RuntimeException e) {
            try { closeServers(new ArrayList<>(opened)); } catch (IOException cleanup) { e.addSuppressed(cleanup); }
            throw e;
        } finally {
            if (!committed) {
                synchronized (swarms) { swarms.remove(name); }
                if (gossipFile != null) { gossipFile.forget(name); }
            }
        }
    }

    private ServerInstance startOne(String swarm, int index, ConcurrentLinkedQueue<ServerInstance> opened) throws IOException {
        var server = factory.start(new ServerOptions(GossipConfigFile.id(swarm, index), 0, 0, pluginDirectories));
        opened.add(server);
        return server;
    }

    /** Does not wait for a swarm that is still starting, so the UI keeps polling while a large swarm comes up. */
    public Map<String, List<ServerEndpoint>> list() {
        var snapshot = new LinkedHashMap<String, List<ServerEndpoint>>();
        synchronized (swarms) { swarms.forEach((name, servers) -> snapshot.put(name, endpoints(servers))); }
        return Collections.unmodifiableMap(snapshot);
    }

    public List<ServerStatus> status(String name) {
        var statuses = new ArrayList<ServerStatus>();
        for (var endpoint : snapshot(name)) { statuses.add(statusOf(endpoint)); }
        return List.copyOf(statuses);
    }

    ServerStatus statusOf(ServerEndpoint endpoint) {
        try {
            String detail = adminStatus(endpoint);
            return new ServerStatus(endpoint, detail.equals("Server status: ALIVE"), detail);
        } catch (IOException e) {
            return new ServerStatus(endpoint, false, e.getMessage());
        }
    }

    /**
     * Sends one command to any listed server, running or not, and returns everything that came back: the line sent, the raw
     * reply, its parsed fields, the error if there was one, and why there was no reply if there was none. See {@link ServerCommand}.
     */
    public Map<String, Object> command(String serverId, String input) {
        ensureOpen();
        var endpoint = list().values().stream().flatMap(List::stream)
                .filter(e -> e.serverId().equals(serverId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown server: " + serverId));
        return ServerCommand.run(endpoint, input);
    }

    /** Runs one command on one server over a kept-open connection; an error reply is returned, not thrown. */
    CommandReply execute(ServerEndpoint endpoint, String instruction, Map<String, Object> parameters) throws IOException {
        return connections.execute(endpoint, instruction, parameters);
    }

    ConnectionPool connections() { return connections; }

    /** Ports of servers that answered the gossip check with "unknown instruction", so the plugin is not loaded there. */
    private final java.util.Set<Integer> withoutGossip = java.util.concurrent.ConcurrentHashMap.newKeySet();

    boolean knownWithoutGossip(int port) { return withoutGossip.contains(port); }
    void markWithoutGossip(int port) { withoutGossip.add(port); }

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
        // One kept-open connection per caller, not one per request: 100,000 requests must not leave 100,000 sockets in TIME_WAIT.
        try (var pool = new ConnectionPool(concurrency); var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = new ArrayList<Future<?>>();
            for (int i = 0; i < requests; i++) {
                int index = i;
                tasks.add(workers.submit(() -> {
                    boolean acquired = false;
                    try {
                        permits.acquire();
                        acquired = true;
                        var target = targets.get(index % targets.size());
                        String message = "swarm request " + index;
                        var reply = pool.execute(target, "echo", Map.of("text", message));
                        if (!("Received: " + message).equals(reply.result())) {
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

    /** Closes one server without telling its peers. It stays listed, so it can be restarted. */
    public synchronized void stopServer(String serverId) throws IOException {
        ensureOpen();
        var at = locate(serverId);
        try { at.servers().get(at.position()).close(); }
        catch (RuntimeException e) { throw new IOException("Cannot stop " + serverId, e); }
    }

    /** Starts the server again on the same ports with the same ID; a gossip server comes back with a new boot ID. */
    public synchronized ServerEndpoint restartServer(String serverId) throws IOException {
        ensureOpen();
        var at = locate(serverId);
        var old = at.servers().get(at.position());
        int port = old.getPort(), adminPort = old.getAdminPort();
        try { old.close(); }
        catch (RuntimeException e) { throw new IOException("Cannot stop " + serverId + " before restarting", e); }
        ServerInstance fresh;
        try { fresh = factory.start(new ServerOptions(serverId, port, adminPort, pluginDirectories)); }
        catch (IOException | RuntimeException e) { throw new IOException("Cannot restart " + serverId + ": " + e.getMessage(), e); }
        var updated = new ArrayList<>(at.servers());
        updated.set(at.position(), fresh);
        synchronized (swarms) { swarms.put(at.swarm(), List.copyOf(updated)); }
        return new ServerEndpoint(serverId, fresh.getPort(), fresh.getAdminPort());
    }

    /**
     * Starts {@code count} more servers in a running swarm, numbered after the highest ID it has had. With gossip, each is
     * seeded at the swarm's first server. Each server is listed as soon as it is up, so a failure part-way keeps the ones
     * that started.
     */
    public synchronized List<ServerEndpoint> addServers(String name, int count) throws IOException {
        ensureOpen();
        List<ServerInstance> current;
        synchronized (swarms) { current = swarms.get(name); }
        if (current == null) { throw new IllegalArgumentException("Unknown swarm: " + name); }
        if (count < 1) { throw new IllegalArgumentException("Server count must be at least 1"); }
        if (current.size() + count > MAX_SERVERS_PER_SWARM) {
            throw new IllegalArgumentException("A swarm holds at most " + MAX_SERVERS_PER_SWARM + " servers; "
                    + name + " has " + current.size());
        }
        int next = current.stream().mapToInt(s -> indexOf(s.getServerId())).max().orElse(0) + 1;
        if (next + count - 1 > 999) { throw new IllegalArgumentException("Server numbers run out at 999 in " + name); }
        var servers = new ArrayList<>(current);
        var seed = current.getFirst();
        var added = new ArrayList<ServerEndpoint>();
        for (int i = 0; i < count; i++) {
            int index = next + i;
            try {
                if (gossip != null && gossipFile != null) { gossipFile.declareServer(name, index, seed.getServerId(), seed.getPort()); }
                var server = factory.start(new ServerOptions(GossipConfigFile.id(name, index), 0, 0, pluginDirectories));
                servers.add(server);
                synchronized (swarms) { swarms.put(name, List.copyOf(servers)); }
                added.add(new ServerEndpoint(server.getServerId(), server.getPort(), server.getAdminPort()));
            } catch (IOException | RuntimeException e) {
                if (gossipFile != null) { gossipFile.forgetServer(GossipConfigFile.id(name, index)); }
                throw new IOException("Added " + added.size() + " of " + count + " servers to " + name + "; "
                        + GossipConfigFile.id(name, index) + " failed: " + e.getMessage(), e);
            }
        }
        return List.copyOf(added);
    }

    /**
     * Takes one server out of its swarm for good: a gossip server first publishes a graceful departure (best effort), then
     * it is closed and unlisted. The swarm is dropped when its last server goes. Unlike {@link #stopServer} the server
     * cannot be restarted.
     */
    public void removeServer(String serverId) throws IOException {
        ServerEndpoint endpoint;
        synchronized (this) {
            ensureOpen();
            var at = locate(serverId);
            endpoint = endpoints(at.servers()).get(at.position());
        }
        if (gossip != null) {
            try { execute(endpoint, "gossip", Map.of("operation", "leave")); }
            catch (IOException | RuntimeException ignored) { /* already stopped, or not answering: close it anyway */ }
        }
        synchronized (this) {
            ensureOpen();
            var at = locate(serverId);
            try { at.servers().get(at.position()).close(); }
            catch (RuntimeException e) { throw new IOException("Cannot close " + serverId, e); }
            var remaining = new ArrayList<>(at.servers());
            remaining.remove(at.position());
            synchronized (swarms) {
                if (remaining.isEmpty()) { swarms.remove(at.swarm()); } else { swarms.put(at.swarm(), List.copyOf(remaining)); }
            }
            if (gossipFile != null) {
                if (remaining.isEmpty()) { gossipFile.forget(at.swarm()); } else { gossipFile.forgetServer(serverId); }
            }
        }
    }

    private static int indexOf(String serverId) {
        var matcher = SERVER_ID.matcher(serverId);
        return matcher.matches() ? Integer.parseInt(matcher.group(2)) : 0;
    }

    /** Asks the server to publish a graceful departure to its gossip peers; it keeps running afterwards. */
    public CommandReply leaveServer(String serverId) throws IOException {
        if (gossip == null) { throw new IllegalStateException("Gossip is not enabled; start the harness with --gossip"); }
        ServerEndpoint endpoint;
        synchronized (this) {
            ensureOpen();
            var at = locate(serverId);
            endpoint = endpoints(at.servers()).get(at.position());
        }
        var reply = execute(endpoint, "gossip", Map.of("operation", "leave"));
        if (!"OK".equals(reply.status())) {
            throw new IOException("Leave rejected: " + (reply.error() == null ? reply.status() : reply.error().message()));
        }
        return reply;
    }

    private record Located(String swarm, List<ServerInstance> servers, int position) { }

    private Located locate(String serverId) {
        var matcher = SERVER_ID.matcher(serverId == null ? "" : serverId);
        if (matcher.matches()) {
            var servers = swarms.get(matcher.group(1));
            if (servers != null) {
                for (int position = 0; position < servers.size(); position++) {
                    if (servers.get(position).getServerId().equals(serverId)) {
                        return new Located(matcher.group(1), servers, position);
                    }
                }
            }
        }
        throw new IllegalArgumentException("Unknown server: " + serverId);
    }

    public synchronized void stop(String name) throws IOException {
        ensureOpen();
        List<ServerInstance> servers;
        synchronized (swarms) { servers = swarms.remove(name); }
        if (servers == null) { throw new IllegalArgumentException("Unknown swarm: " + name); }
        if (gossipFile != null) { gossipFile.forget(name); }
        closeServers(servers);
    }

    public synchronized void stopAll() throws IOException {
        List<ServerInstance> servers;
        java.util.List<String> names;
        synchronized (swarms) {
            servers = swarms.values().stream().flatMap(List::stream).toList();
            names = List.copyOf(swarms.keySet());
            swarms.clear();
        }
        if (gossipFile != null) { names.forEach(gossipFile::forget); }
        closeServers(servers);
    }

    @Override public synchronized void close() throws IOException {
        if (!closed) {
            closed = true;
            try { stopAll(); }
            finally {
                connections.close();
                if (gossipFile != null) { gossipFile.close(); }
            }
        }
    }

    private List<ServerEndpoint> snapshot(String name) {
        ensureOpen();
        List<ServerInstance> servers;
        synchronized (swarms) { servers = swarms.get(name); }
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
