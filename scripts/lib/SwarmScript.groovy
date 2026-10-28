import groovy.json.JsonOutput
import groovy.json.JsonSlurper

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * The base of every swarm script. A script declares it with
 * {@code @groovy.transform.BaseScript SwarmScript swarm} and then uses its verbs directly: {@code kill 'kv-Server-002'},
 * {@code put 'kv-Server-001', 'colour', 'blue'}, {@code awaitInSync 'kv'}.
 *
 * <p>Servers are started, stopped and restarted through the running harness (its control endpoints on
 * {@code SWARM_HARNESS}, by default http://localhost:8088), because the harness owns them. Everything else goes straight
 * to a server over its own protocol: one JSON line per request on a TCP connection that is kept open and reused.
 *
 * <p>{@link #check} records a failure without stopping the script; the script exits with status 1 if any check failed,
 * or 2 if it threw.
 */
abstract class SwarmScript extends Script {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern('HH:mm:ss.SSS')

    final String harness = System.getenv('SWARM_HARNESS') ?: 'http://localhost:8088'
    private final HttpClient http = HttpClient.newHttpClient()
    private final JsonSlurper json = new JsonSlurper()
    /** Each server's main port, by server ID; refreshed from the harness when a server is not found or does not answer. */
    private final Map<String, Integer> ports = [:]
    private final Map<Integer, ServerLink> links = [:]
    private final Random random = new Random()
    /** Different on every run, so the keys one run writes are never mistaken for those an earlier run left behind. */
    final String runId = Long.toString(System.currentTimeMillis(), 36)
    private int checks
    private int failures

    /** The script's own body, which Groovy compiles into this method. */
    abstract Object body()

    @Override Object run() {
        int status = 0
        try {
            body()
        } catch (Throwable failure) {
            log "Script failed: ${failure}"
            status = 2
        } finally {
            links.values()*.close()
        }
        if (status == 0 && failures > 0) { status = 1 }
        log "Done | checks=${checks} | failed=${failures} | ${status == 0 ? 'PASSED' : 'FAILED'}"
        System.exit(status)
    }

    // ---- The harness: swarms and servers ------------------------------------------------------------------------

    /** Every swarm and server, as the harness page sees them. */
    Map snapshot() { request('GET', '/api/snapshot', null) as Map }

    Map swarm(String name) {
        def found = snapshot().swarms.find { it.name == name }
        if (!found) { throw new IllegalArgumentException("No swarm named ${name}") }
        found as Map
    }

    List<Map> servers(String name) { swarm(name).servers as List<Map> }

    /** The IDs of the servers in a swarm that are up and still members: running, and not departed from gossip. */
    List<String> alive(String name) { members(servers(name))*.serverId as List<String> }

    /** A server that has left gossip keeps running, but its peers no longer count it or replicate to it. */
    private static List<Map> members(List<Map> servers) {
        servers.findAll { it.alive && it.gossip?.status?.self?.state != 'LEFT' }
    }

    /** A server of the swarm chosen at random, among the members up unless {@code anyState} is set. */
    String randomServer(String name, boolean anyState = false) {
        def ids = anyState ? servers(name)*.serverId : alive(name)
        if (!ids) { throw new IllegalStateException("Swarm ${name} has no server to choose") }
        ids[random.nextInt(ids.size())]
    }

    List<String> start(String name, int count) {
        def started = request('POST', '/api/swarms', [name: name, servers: count]).servers
        log "Started swarm ${name} | servers=${started}"
        started as List<String>
    }

    List<String> add(String name, int count = 1) {
        def added = request('POST', "/api/swarms/${name}/servers", [count: count]).servers
        log "Added to ${name} | servers=${added}"
        added as List<String>
    }

    /** Stops a server without telling its peers, as a crash would. It stays listed and can be restarted. */
    void kill(String serverId) { serverAction(serverId, 'stop', 'Killed') }

    /** Starts a stopped server again on the same ports; with gossip it rejoins under a new boot ID. */
    void restart(String serverId) { serverAction(serverId, 'restart', 'Restarted') }

    /**
     * Publishes a graceful gossip departure. The server keeps running but is no longer a member: its peers stop counting
     * it and replicating to it, and {@link #alive} leaves it out. {@link #remove} takes it away.
     */
    void leave(String serverId) { serverAction(serverId, 'leave', 'Left') }

    /**
     * Freezes a server, as a debugger or a long pause would: it keeps its connections but answers and does nothing, so
     * its peers see it fall silent rather than go away. {@link #alive} leaves it out until it is thawed.
     */
    void freeze(String serverId) { serverAction(serverId, 'freeze', 'Froze') }

    void thaw(String serverId) { serverAction(serverId, 'thaw', 'Thawed') }

    /** Freezes a server for a while, then thaws it, whatever happens meanwhile. */
    void frozenFor(String serverId, long millis, Closure meanwhile = {}) {
        freeze serverId
        try {
            long until = System.currentTimeMillis() + millis
            meanwhile.call()
            long left = until - System.currentTimeMillis()
            if (left > 0) { Thread.sleep(left) }
        } finally {
            thaw serverId
        }
    }

    // ---- The network between the servers ---------------------------------------------------------------------

    /**
     * Cuts the swarm into sides that cannot reach one another, in either direction: {@code partition(['a-Server-001'],
     * ['a-Server-002', 'a-Server-003'])}. Each server still answers scripts and the harness. Replaces an earlier partition.
     */
    Map partition(List<String>... sides) {
        def rules = request('POST', '/api/network/partition', [sides: sides as List]) as Map
        log "Partitioned ${sides.collect { it.join(', ') }.join('  |  ')}"
        rules
    }

    /** Cuts one server off from every other server of its swarm. */
    Map isolate(String serverId) {
        String swarmName = serverId.replaceFirst(/-Server-\d+$/, '')
        partition([serverId], servers(swarmName)*.serverId.findAll { it != serverId } as List<String>)
    }

    /** Adds {@code millis} to every request to or from each server; 0 removes it. */
    Map delay(List<String> serverIds, long millis) {
        def rules = request('POST', '/api/network/delay', [servers: serverIds, millis: millis]) as Map
        log "Delay ${millis} ms to and from ${serverIds}"
        rules
    }

    /** Loses {@code percent} of the requests to or from each server; 0 removes it. */
    Map loss(List<String> serverIds, int percent) {
        def rules = request('POST', '/api/network/loss', [servers: serverIds, percent: percent]) as Map
        log "Loss ${percent}% to and from ${serverIds}"
        rules
    }

    /** A healthy network again: no partition, no delay, no loss. */
    Map heal() {
        def rules = request('POST', '/api/network/heal', [:]) as Map
        log 'Healed the network'
        rules
    }

    /** The network rules in force. */
    Map network() { request('GET', '/api/network', null) as Map }

    /** Takes a server out of its swarm for good. */
    void remove(String serverId) {
        serverAction(serverId, 'remove', 'Removed')
        ports.remove(serverId)
    }

    void stopSwarm(String name) {
        request('POST', "/api/swarms/${name}/stop", [:])
        log "Stopped swarm ${name}"
    }

    /** A verified echo load test across the swarm: requests sent round-robin, {@code concurrency} at a time. */
    Map load(String name, int requests = 1000, int concurrency = 100) {
        def result = request('POST', "/api/swarms/${name}/test", [requests: requests, concurrency: concurrency]) as Map
        log "Load test ${name} | requests=${result.requests} | passed=${result.passed} | failed=${result.failed} | ms=${result.elapsedMillis}"
        result
    }

    private void serverAction(String serverId, String action, String done) {
        request('POST', "/api/servers/${serverId}/${action}", [:])
        closeLink(serverId)
        log "${done} ${serverId}"
    }

    // ---- The servers, over their own protocol --------------------------------------------------------------------

    /** Runs one instruction on one server and returns its result; an error reply is thrown. */
    Object call(String serverId, String instruction, Map parameters = [:]) {
        def line = JsonOutput.toJson([version: 1, messageId: UUID.randomUUID().toString(), instruction: instruction,
                                      parameters: parameters, connection: 'keep-alive'])
        Map reply
        try { reply = link(serverId).exchange(line) }
        catch (IOException firstTry) {
            // The server may have been restarted on other ports, or replaced: look it up again once.
            ports.remove(serverId)
            closeLink(serverId)
            reply = link(serverId).exchange(line)
        }
        if (reply.status != 'OK') { throw new IllegalStateException("${serverId}: ${reply.error?.code} ${reply.error?.message}") }
        reply.result
    }

    Object kv(String serverId, Map parameters) { call(serverId, 'kv', parameters) }

    /** A key for this run only: {@code key('split')} is {@code split-<runId>}. The store keeps keys across runs. */
    String key(String name) { "${name}-${runId}" }

    void put(String serverId, String key, Object value) { kv(serverId, [operation: 'put', key: key, value: value]) }

    /** The value held for a key, or null when the server holds none. */
    Object get(String serverId, String key) {
        def found = kv(serverId, [operation: 'get', key: key])
        found.found ? found.value : null
    }

    void delete(String serverId, String key) { kv(serverId, [operation: 'delete', key: key]) }

    List<String> keys(String serverId) { kv(serverId, [operation: 'list']).keys as List<String> }

    Map kvStatus(String serverId) { kv(serverId, [operation: 'status']) as Map }

    /** Gossip's view from one server: how many members it rates ALIVE, SUSPECT, DEAD and so on. */
    Map assessments(String serverId) { call(serverId, 'gossip', [operation: 'status']).assessments as Map }

    /** The boot ID a server runs under now; a restart gives it a new one. */
    String bootId(String serverId) { call(serverId, 'gossip', [operation: 'status']).self.bootId }

    /**
     * How one server rates one boot of another in its gossip view: ALIVE, SUSPECT, DEAD, LEFT or CANDIDATE, or null when
     * it holds no record of that boot. Counting assessments cannot say this once old boots are retained beside it.
     */
    String rating(String serverId, String memberId, String memberBootId) {
        String cursor = null
        while (true) {
            def page = call(serverId, 'gossip', cursor ? [operation: 'members', cursor: cursor] : [operation: 'members'])
            def row = page.members.find { it.serverId == memberId && it.bootId == memberBootId }
            if (row) { return row.assessment }
            cursor = page.nextCursor
            if (!cursor) { return null }
        }
    }

    // ---- Waiting and checking ----------------------------------------------------------------------------------

    /**
     * Waits until every member of the swarm that is up holds the same pairs (the same root hash). Returns whether it
     * happened in time, and records that as a check.
     */
    boolean awaitInSync(String name, long timeoutMillis = 20_000) {
        boolean ok = waitFor("${name} in sync", timeoutMillis) {
            def roots = members(servers(name)).collect { it.kv?.root }
            roots && !roots.contains(null) && roots.unique(false).size() == 1
        }
        check ok, "every member of ${name} that is up holds the same pairs"
        ok
    }

    /** Waits until every member of the swarm that is up rates {@code count} members ALIVE in its gossip view. */
    boolean awaitAlive(String name, int count, long timeoutMillis = 30_000) {
        boolean ok = waitFor("${name} sees ${count} alive", timeoutMillis) {
            def up = members(servers(name))
            up.size() == count && up.every { it.gossip?.status?.assessments?.ALIVE == count }
        }
        check ok, "every server of ${name} that is up rates ${count} members ALIVE"
        ok
    }

    /** Polls a condition every half second until it holds or the time is up. */
    boolean waitFor(String what, long timeoutMillis, Closure<Boolean> condition) {
        long deadline = System.currentTimeMillis() + timeoutMillis
        long started = System.currentTimeMillis()
        while (true) {
            boolean holds = false
            try { holds = condition.call() } catch (Exception notYet) { /* a server may be restarting */ }
            if (holds) {
                log "${what} | after ${System.currentTimeMillis() - started} ms"
                return true
            }
            if (System.currentTimeMillis() > deadline) {
                log "${what} | not after ${timeoutMillis} ms"
                return false
            }
            Thread.sleep(500)
        }
    }

    /** Records a check: a failure is reported and counted, and the script carries on. */
    boolean check(boolean condition, String what) {
        checks++
        if (!condition) { failures++ }
        log "${condition ? 'PASS' : 'FAIL'} | ${what}"
        condition
    }

    void pause(long millis) { Thread.sleep(millis) }

    void log(Object message) { println "[${LocalTime.now().format(TIME)}] ${message}" }

    // ---- Chaos experiments --------------------------------------------------------------------------------------

    /**
     * A chaos experiment in the usual shape: the steady state must hold before; the method then breaks something; the
     * steady state must hold again afterwards; and the rollback always runs, even when the method failed, so the swarm
     * is left as it was found. {@code parts} holds closures named {@code steady} (returning whether the steady state
     * holds), {@code method} and, optionally, {@code rollback}.
     */
    boolean experiment(String title, Map<String, Closure> parts) {
        log "=== Experiment: ${title}"
        if (!check(parts.steady.call() as boolean, "steady state holds before: ${title}")) {
            log "=== Aborted: the swarm was not steady to begin with"
            return false
        }
        boolean ok = false
        try {
            parts.method.call()
            ok = check(parts.steady.call() as boolean, "steady state holds after: ${title}")
        } catch (Exception failure) {
            check false, "method ran without error: ${failure}"
        } finally {
            if (parts.rollback) {
                try { parts.rollback.call() }
                catch (Exception failure) { check false, "rollback ran without error: ${failure}" }
            }
        }
        log "=== ${ok ? 'Held' : 'Broke'}: ${title}"
        ok
    }

    // ---- Plumbing -----------------------------------------------------------------------------------------------

    private Object request(String method, String path, Map body) {
        def builder = HttpRequest.newBuilder(URI.create(harness + path))
        if (method == 'POST') {
            // The harness accepts actions only from its own page; a script on this machine says it is one.
            builder.header('Origin', harness).header('X-Swarm-UI', 'script').header('Content-Type', 'application/json')
                    .POST(HttpRequest.BodyPublishers.ofString(JsonOutput.toJson(body ?: [:])))
        } else {
            builder.GET()
        }
        def response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        def parsed = response.body() ? json.parseText(response.body()) : [:]
        if (response.statusCode() != 200) {
            throw new IllegalStateException("${method} ${path}: ${response.statusCode()} ${parsed instanceof Map ? parsed.error : parsed}")
        }
        parsed
    }

    private synchronized ServerLink link(String serverId) {
        if (!ports.containsKey(serverId)) {
            snapshot().swarms.each { s -> s.servers.each { ports[it.serverId] = it.mainPort as Integer } }
            if (!ports.containsKey(serverId)) { throw new IllegalArgumentException("No server named ${serverId}") }
        }
        int port = ports[serverId]
        links.computeIfAbsent(port) { new ServerLink('localhost', port) }
    }

    private synchronized void closeLink(String serverId) {
        def port = ports[serverId]
        if (port != null) { links.remove(port)?.close() }
    }

    /** One kept-open connection to a server. A request on one the server has closed meanwhile is sent once more on a new one. */
    private static final class ServerLink {
        final String host
        final int port
        private Socket socket
        private BufferedReader reader
        private Writer writer

        ServerLink(String host, int port) { this.host = host; this.port = port }

        synchronized Map exchange(String line) {
            for (int attempt = 0; ; attempt++) {
                boolean reused = socket != null
                try {
                    if (socket == null) { open() }
                    writer.write(line + '\n')
                    writer.flush()
                    String reply = reader.readLine()
                    if (reply == null) { throw new IOException("Server on port ${port} closed the connection without replying") }
                    return new JsonSlurper().parseText(reply) as Map
                } catch (IOException e) {
                    close()
                    if (!reused || attempt > 0) { throw e }
                }
            }
        }

        private void open() {
            socket = new Socket()
            socket.connect(new InetSocketAddress(host, port), 5000)
            socket.soTimeout = 10_000
            reader = new BufferedReader(new InputStreamReader(socket.inputStream, 'UTF-8'))
            writer = new OutputStreamWriter(socket.outputStream, 'UTF-8')
        }

        synchronized void close() {
            try { socket?.close() } catch (IOException ignored) { }
            socket = null
        }
    }
}
