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
import java.nio.file.Files;
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
    /** Every server of a Raft swarm votes, and more voters only slow it. */
    public static final int MAX_RAFT_SERVERS = 9;
    /**
     * How long finding a Raft swarm's leader and having it change the voters may take. Longer than the plugin's own catch.up.timeout.millis,
     * so the plugin, which knows why, is the one that gives up.
     */
    private static final long RAFT_CHANGE_MILLIS = 30_000;
    private static final java.util.regex.Pattern SERVER_ID = java.util.regex.Pattern.compile("(.+)-Server-(\\d{3})");
    private final Map<String, List<ServerInstance>> swarms = new LinkedHashMap<>();
    private final List<Path> pluginDirectories;
    private final ServerFactory factory;
    private final GossipSettings gossip;
    private final boolean kvReplicate;
    private KvConfigFile kvFile;
    private GossipConfigFile gossipFile;
    private RaftConfigFile raftFile;
    private final java.util.Set<String> raftSwarms = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Each Raft swarm's cluster ID, which a server added to it must be given. */
    private final Map<String, String> raftClusterIds = new java.util.concurrent.ConcurrentHashMap<>();
    /** The plugin folders of each server that runs without one of them for now. */
    private final Map<String, List<Path>> heldBack = new java.util.concurrent.ConcurrentHashMap<>();
    private final ConnectionPool connections = new ConnectionPool();
    private final NetworkChaos network = new NetworkChaos(this::list);
    private volatile boolean closed;

    public SwarmManager() { this(List.of()); }
    public SwarmManager(Path pluginsDir) { this(pluginsDir == null ? List.of() : List.of(pluginsDir)); }
    public SwarmManager(List<Path> pluginDirectories) { this(pluginDirectories, ServerInstance::start); }
    /** With gossip settings, every swarm's servers get a generated gossip config; the plugin directories must hold the plugin. */
    public SwarmManager(List<Path> pluginDirectories, GossipSettings gossip) {
        this(pluginDirectories, gossip, ServerInstance::start);
    }
    /** With {@code kvReplicate}, every server runs the key-value plugin with replication on, which needs gossip for its peers. */
    public SwarmManager(List<Path> pluginDirectories, GossipSettings gossip, boolean kvReplicate) {
        this(pluginDirectories, gossip, kvReplicate, ServerInstance::start);
    }
    SwarmManager(List<Path> pluginDirectories, ServerFactory factory) { this(pluginDirectories, null, factory); }
    SwarmManager(List<Path> pluginDirectories, GossipSettings gossip, ServerFactory factory) {
        this(pluginDirectories, gossip, false, factory);
    }

    SwarmManager(List<Path> pluginDirectories, GossipSettings gossip, boolean kvReplicate, ServerFactory factory) {
        this.pluginDirectories = List.copyOf(pluginDirectories);
        this.gossip = gossip;
        this.kvReplicate = kvReplicate;
        this.factory = factory;
    }

    public boolean gossipEnabled() { return gossip != null; }

    /** The network between this harness's servers: partitions, delay and loss, for chaos experiments. */
    NetworkChaos network() { return network; }

    /**
     * Starts a swarm whose servers form a Raft cluster: every one a voter, from the moment each is told so. The plugin is loaded on
     * every server of the harness, and stays idle on any not told. Servers keep their logs under a directory this harness owns, through
     * restarts, until the swarm is stopped. Adding a server makes it a voter once it has caught up, and removing one takes its vote away
     * first, one server at a time, through the log. With key-value replication on for the harness, it is off for these servers, because
     * Raft and kv's own replication at once would be silently wrong.
     */
    public synchronized List<ServerEndpoint> startRaft(String name, int count) throws IOException { return startRaft(name, count, null); }

    /**
     * A Raft swarm whose servers start without one plugin, named as in its jar's name ({@code kv} for {@code kv-plugin-*.jar}), so commands
     * for it commit and wait to be applied. {@link #restartServerWithAllPlugins} then activates it, one server at a time.
     */
    public synchronized List<ServerEndpoint> startRaft(String name, int count, String without) throws IOException {
        ensureOpen();
        if (count < 1 || count > MAX_RAFT_SERVERS) { throw new IllegalArgumentException("A Raft swarm holds from 1 to " + MAX_RAFT_SERVERS + " servers"); }
        synchronized (swarms) {
            if (swarms.containsKey(name)) { throw new IllegalArgumentException("Swarm already exists: " + name); }
        }
        if (without != null) {
            var kept = directoriesWithout(without);
            for (int i = 1; i <= count; i++) { heldBack.put(GossipConfigFile.id(name, i), kept); }
        }
        if (raftFile == null) { raftFile = RaftConfigFile.open(); }
        if (kvReplicate) {
            if (kvFile == null) { kvFile = KvConfigFile.open(); }
            kvFile.keepLocal(name, count);
        }
        boolean started = false;
        try {
            var endpoints = start(name, count);
            started = true;
            raftSwarms.add(name);
            bootstrap(name, endpoints);
            return endpoints;
        } catch (IOException | RuntimeException failed) {
            try {
                forgetHeldBack(name);
                if (started) { stop(name); }
                else if (kvFile != null) { kvFile.forget(name); }
            } catch (IOException | RuntimeException cleanup) { failed.addSuppressed(cleanup); }
            throw failed;
        }
    }

    /** The plugin folders without the one holding this plugin. */
    private List<Path> directoriesWithout(String plugin) throws IOException {
        var kept = new ArrayList<Path>();
        for (var directory : pluginDirectories) {
            boolean holdsIt;
            try (var files = Files.list(directory)) {
                holdsIt = files.map(file -> file.getFileName().toString()).anyMatch(file -> file.startsWith(plugin + "-plugin") && file.endsWith(".jar"));
            }
            if (!holdsIt) { kept.add(directory); }
        }
        if (kept.size() == pluginDirectories.size()) { throw new IllegalArgumentException("No plugin folder holds a " + plugin + "-plugin jar"); }
        return List.copyOf(kept);
    }

    private List<Path> directoriesFor(String serverId) { return heldBack.getOrDefault(serverId, pluginDirectories); }

    private void forgetHeldBack(String swarm) { heldBack.keySet().removeIf(id -> id.startsWith(swarm + "-Server-")); }

    private void bootstrap(String name, List<ServerEndpoint> servers) throws IOException {
        var voters = new ArrayList<Map<String, Object>>();
        for (var server : servers) { voters.add(Map.of("id", server.serverId(), "host", "127.0.0.1", "port", server.port())); }
        var clusterId = name + "-" + UUID.randomUUID();
        raftClusterIds.put(name, clusterId);
        for (var server : servers) {
            var reply = execute(server, "raft", Map.of("operation", "bootstrap", "clusterId", clusterId, "voters", voters));
            if (reply.error() == null) { continue; }
            if (reply.error().code().name().equals("UNKNOWN_INSTRUCTION")) {
                throw new IOException("The Raft plugin is not loaded: start the harness with --plugins-dir pointing at it");
            }
            throw new IOException("Could not bootstrap " + server.serverId() + ": " + reply.error().message());
        }
    }

    public boolean isRaft(String swarm) { return raftSwarms.contains(swarm); }

    /** Whether this server belongs to a Raft swarm, so there is something to ask it. */
    boolean inRaftSwarm(String serverId) {
        var id = SERVER_ID.matcher(serverId);
        return id.matches() && isRaft(id.group(1));
    }

    RaftConfigFile raftFile() { return raftFile; }

    /**
     * Gives a server just started in a Raft swarm the cluster, without a vote, then has the leader add it, which it does once the server
     * has caught up. Returns once that change has committed.
     */
    private void joinAndAdd(String swarm, ServerEndpoint joining) throws IOException {
        var voters = new ArrayList<Map<String, Object>>();
        for (var server : endpointsOf(swarm)) {
            if (!server.serverId().equals(joining.serverId())) { voters.add(Map.of("id", server.serverId(), "host", "127.0.0.1", "port", server.port())); }
        }
        var joined = execute(joining, "raft", Map.of("operation", "join", "clusterId", raftClusterIds.get(swarm), "voters", voters));
        if (joined.error() != null) { throw new IOException("Could not join " + joining.serverId() + " to " + swarm + ": " + joined.error().message()); }
        changeVoters(swarm, Map.of("operation", "addVoter", "id", joining.serverId(), "host", "127.0.0.1", "port", joining.port()), null);
    }

    /**
     * Has whoever leads the swarm make a change of voters, following the lead through elections, and returns once the change has committed.
     * A refusal that waiting will not cure is thrown as {@link IllegalStateException}; an outcome not known, as {@link IOException}. With
     * {@code removing}, a server the leader does not count as a voter needs no change.
     */
    @SuppressWarnings("unchecked")
    private void changeVoters(String swarm, Map<String, Object> change, String removing) throws IOException {
        long deadline = System.nanoTime() + RAFT_CHANGE_MILLIS * 1_000_000;
        String last = "no server of " + swarm + " leads";
        while (System.nanoTime() < deadline) {
            for (var server : endpointsOf(swarm)) {
                var status = raftStatus(server);
                if (!"LEADER".equals(status.get("role"))) { continue; }
                if (removing != null && status.get("voters") instanceof List<?> voters && !voters.contains(removing) && !removing.equals(status.get("learner"))) { return; }
                var reply = execute(server, "raft", change);
                if (reply.error() != null) { throw new IOException("Could not change the voters of " + swarm + ": " + reply.error().message()); }
                var result = (Map<String, Object>) reply.result();
                var outcome = String.valueOf(result.get("status"));
                if (outcome.equals("COMMITTED")) { return; }
                last = String.valueOf(result.get("reason"));
                if (outcome.equals("OUTCOME_UNKNOWN")) { throw new IOException("What became of the change to " + swarm + "'s voters is not known: " + last); }
                // A new leader may not have committed in its own term yet, and an earlier change may still be committing: both pass.
                if (outcome.equals("REFUSED") && !last.contains("own term") && !last.contains("one at a time")) { throw new IllegalStateException(last); }
            }
            try { Thread.sleep(50); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Interrupted while changing the voters of " + swarm);
            }
        }
        throw new IOException("Could not change the voters of " + swarm + " within " + RAFT_CHANGE_MILLIS + " ms: " + last);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> raftStatus(ServerEndpoint server) {
        try {
            var reply = execute(server, "raft", Map.of("operation", "status"));
            return reply.error() == null && reply.result() instanceof Map<?, ?> result ? (Map<String, Object>) result : Map.of();
        } catch (IOException notAnswering) {
            return Map.of();
        }
    }

    private List<ServerEndpoint> endpointsOf(String swarm) {
        List<ServerInstance> servers;
        synchronized (swarms) { servers = swarms.getOrDefault(swarm, List.of()); }
        return endpoints(servers);
    }

    private void forgetRaftSwarm(String name, List<ServerInstance> servers) throws IOException {
        forgetHeldBack(name);
        if (kvFile != null) { kvFile.forget(name); }
        raftClusterIds.remove(name);
        if (!raftSwarms.remove(name)) { return; }
        for (var server : servers) { raftFile.forgetServer(server.getServerId()); }
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
            if (kvReplicate && kvFile == null) { kvFile = KvConfigFile.open(); }
            if (gossip != null) {
                if (gossipFile == null) { gossipFile = new GossipConfigFile(gossip); }
                gossipFile.declare(name, count);
            }
            // Platform threads, because starting a server initializes its plugin's classes. A virtual thread in a class initializer
            // cannot give up its carrier, so with more of them than carriers all waiting on a lock on the plugin's jar, the thread
            // holding that lock never gets a carrier to run on, and the whole harness stops.
            try (var starters = Executors.newThreadPerTaskExecutor(Thread.ofPlatform().daemon().factory())) {
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
        var id = GossipConfigFile.id(swarm, index);
        var server = factory.start(new ServerOptions(id, 0, 0, directoriesFor(id)));
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

    /** Ports of servers that answered the kv check with "unknown instruction", so the key-value plugin is not loaded there. */
    private final java.util.Set<Integer> withoutKv = java.util.concurrent.ConcurrentHashMap.newKeySet();

    boolean knownWithoutKv(int port) { return withoutKv.contains(port); }
    void markWithoutKv(int port) { withoutKv.add(port); }
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

    /**
     * Freezes one server, as a debugger or a long pause would: it keeps its connections and state but accepts, answers
     * and does nothing, and its gossip and key-value plugins pause, so its peers see it fall silent. Thaw, stop or
     * restart ends it.
     */
    public synchronized void freezeServer(String serverId) {
        ensureOpen();
        var at = locate(serverId);
        at.servers().get(at.position()).freeze();
    }

    public synchronized void thawServer(String serverId) {
        ensureOpen();
        var at = locate(serverId);
        at.servers().get(at.position()).thaw();
    }

    /** The instructions a server answers, one per command plugin it loaded, in name order; none for one not known. */
    List<String> instructions(String serverId) {
        var server = find(serverId);
        return server == null ? List.of() : server.instructions().stream().sorted().toList();
    }

    /** Whether the server is frozen; one the harness does not know is not. */
    boolean frozen(String serverId) {
        var server = find(serverId);
        return server != null && server.frozen();
    }

    /**
     * A server by ID, for the snapshot. It takes only the swarm map's lock, never this manager's, which a large swarm
     * holds for as long as it takes to start; so the page keeps updating meanwhile.
     */
    private ServerInstance find(String serverId) {
        synchronized (swarms) {
            try {
                var at = locate(serverId);
                return at.servers().get(at.position());
            } catch (IllegalArgumentException unknown) {
                return null;
            }
        }
    }

    /** Starts the server again on the same ports with the same ID; a gossip server comes back with a new boot ID. */
    public synchronized ServerEndpoint restartServer(String serverId) throws IOException {
        ensureOpen();
        var at = locate(serverId);
        var old = at.servers().get(at.position());
        int port = old.getPort(), adminPort = old.getAdminPort();
        try { old.close(); }
        catch (RuntimeException e) { throw new IOException("Cannot stop " + serverId + " before restarting", e); }
        // It may come back with a plugin it did not have, so what was learned about it no longer holds.
        withoutKv.remove(port);
        withoutGossip.remove(port);
        ServerInstance fresh;
        try { fresh = factory.start(new ServerOptions(serverId, port, adminPort, directoriesFor(serverId))); }
        catch (IOException | RuntimeException e) { throw new IOException("Cannot restart " + serverId + ": " + e.getMessage(), e); }
        var updated = new ArrayList<>(at.servers());
        updated.set(at.position(), fresh);
        synchronized (swarms) { swarms.put(at.swarm(), List.copyOf(updated)); }
        return new ServerEndpoint(serverId, fresh.getPort(), fresh.getAdminPort());
    }

    /** Restarts a server held back from a plugin with every plugin, the only way a plugin can be activated, since plugins load at start. */
    public synchronized ServerEndpoint restartServerWithAllPlugins(String serverId) throws IOException {
        ensureOpen();
        locate(serverId);
        heldBack.remove(serverId);
        return restartServer(serverId);
    }

    /**
     * Starts {@code count} more servers in a running swarm, numbered after the highest ID it has had. With gossip, each is
     * seeded at the swarm's first server. Each server is listed as soon as it is up, so a failure part-way keeps the ones
     * that started. In a Raft swarm each is added as a voter before the next starts; one the leader would not add is stopped
     * and unlisted again.
     */
    public synchronized List<ServerEndpoint> addServers(String name, int count) throws IOException {
        ensureOpen();
        List<ServerInstance> current;
        synchronized (swarms) { current = swarms.get(name); }
        if (current == null) { throw new IllegalArgumentException("Unknown swarm: " + name); }
        boolean raft = isRaft(name);
        if (count < 1) { throw new IllegalArgumentException("Server count must be at least 1"); }
        if (raft && current.size() + count > MAX_RAFT_SERVERS) {
            throw new IllegalArgumentException("A Raft swarm holds at most " + MAX_RAFT_SERVERS + " servers; " + name + " has " + current.size());
        }
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
                if (raft && kvFile != null) { kvFile.keepLocalServer(GossipConfigFile.id(name, index)); }
                var server = factory.start(new ServerOptions(GossipConfigFile.id(name, index), 0, 0, pluginDirectories));
                servers.add(server);
                synchronized (swarms) { swarms.put(name, List.copyOf(servers)); }
                var endpoint = new ServerEndpoint(server.getServerId(), server.getPort(), server.getAdminPort());
                if (raft) {
                    try {
                        joinAndAdd(name, endpoint);
                    } catch (IllegalStateException refused) {
                        // Nothing changed, so the server goes again, and its data with it.
                        servers.remove(server);
                        synchronized (swarms) { swarms.put(name, List.copyOf(servers)); }
                        server.close();
                        raftFile.forgetServer(server.getServerId());
                        throw refused;
                    }
                }
                added.add(endpoint);
            } catch (IOException | RuntimeException e) {
                if (gossipFile != null) { gossipFile.forgetServer(GossipConfigFile.id(name, index)); }
                if (raft && kvFile != null) { kvFile.forgetServer(GossipConfigFile.id(name, index)); }
                throw new IOException("Added " + added.size() + " of " + count + " servers to " + name + "; "
                        + GossipConfigFile.id(name, index) + " failed: " + e.getMessage(), e);
            }
        }
        return List.copyOf(added);
    }

    /**
     * Takes one server out of its swarm for good: a gossip server first publishes a graceful departure (best effort), then
     * it is closed and unlisted. The swarm is dropped when its last server goes. Unlike {@link #stopServer} the server
     * cannot be restarted. In a Raft swarm the leader first takes away its vote, and the server is closed only once that has
     * committed. A leader removed hands over: it steps down once its removal commits. The last voter is never removed.
     */
    public void removeServer(String serverId) throws IOException {
        ServerEndpoint endpoint;
        String swarm;
        synchronized (this) {
            ensureOpen();
            var at = locate(serverId);
            swarm = at.swarm();
            endpoint = endpoints(at.servers()).get(at.position());
        }
        if (isRaft(swarm)) { changeVoters(swarm, Map.of("operation", "removeVoter", "id", serverId), serverId); }
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
            if (isRaft(at.swarm())) {
                raftFile.forgetServer(serverId);
                if (kvFile != null) { kvFile.forgetServer(serverId); }
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
        forgetRaftSwarm(name, servers);
    }

    public synchronized void stopAll() throws IOException {
        List<ServerInstance> servers;
        var bySwarm = new LinkedHashMap<String, List<ServerInstance>>();
        synchronized (swarms) {
            servers = swarms.values().stream().flatMap(List::stream).toList();
            bySwarm.putAll(swarms);
            swarms.clear();
        }
        if (gossipFile != null) { bySwarm.keySet().forEach(gossipFile::forget); }
        closeServers(servers);
        for (var swarm : bySwarm.entrySet()) { forgetRaftSwarm(swarm.getKey(), swarm.getValue()); }
    }

    @Override public synchronized void close() throws IOException {
        if (!closed) {
            closed = true;
            try { stopAll(); }
            finally {
                network.heal();
                connections.close();
                if (gossipFile != null) { gossipFile.close(); }
                if (kvFile != null) { kvFile.close(); }
                if (raftFile != null) { raftFile.close(); }
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
