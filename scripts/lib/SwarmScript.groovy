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
    private final Map<String, Integer> ports = new java.util.concurrent.ConcurrentHashMap<>()
    /** One connection to a port for each thread, because a slow answer must hold up only the client that asked. */
    private final Map<String, ServerLink> links = [:]
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

    // ---- Raft -----------------------------------------------------------------------------------------------------

    /**
     * Starts a swarm whose servers form a Raft cluster, which needs the Raft plugin in the harness's --plugins-dir. With {@code without}
     * they start without that plugin, {@code 'kv'} say, until {@link #restartWithAllPlugins} gives it to them.
     */
    List<String> startRaft(String name, int count, String without = null) {
        def body = [name: name, servers: count, mode: 'raft']
        if (without) { body.without = without }
        def started = request('POST', '/api/swarms', body).servers
        log "Started Raft swarm ${name} | servers=${started}${without ? ', without ' + without : ''}"
        started as List<String>
    }

    /** Restarts a server held back from a plugin with all of them: plugins load only at start. */
    void restartWithAllPlugins(String serverId) { serverAction(serverId, 'restart-with-all-plugins', 'Restarted with all plugins') }

    Map raftStatus(String serverId) { call(serverId, 'raft', [operation: 'status']) as Map }

    /** Asks one server to commit a command. The reply's status is COMMITTED, NOT_LEADER, REFUSED or OUTCOME_UNKNOWN. */
    Map propose(String serverId, Map command) { call(serverId, 'raft', [operation: 'propose', command: command]) as Map }

    /** Runs a command on one server, if it leads and a majority confirms that. The reply's status is READ, NOT_LEADER, REFUSED or OUTCOME_UNKNOWN. */
    Map raftRead(String serverId, Map command) { call(serverId, 'raft', [operation: 'read', command: command]) as Map }

    /** A kv write, as a command for {@link #propose}. */
    Map kvPut(String key, Object value) { [instruction: 'kv', parameters: [operation: 'put', key: key, value: value]] }

    /** A kv read, as a command for {@link #raftRead}. */
    Map kvGet(String key) { [instruction: 'kv', parameters: [operation: 'get', key: key]] }

    /** Reads through one server, as a client does: if that server does not lead, it names who does, and that one is asked. Null if nothing could be read in time. */
    Map readThrough(String serverId, Map command, long timeoutMillis = 15_000) {
        long deadline = System.currentTimeMillis() + timeoutMillis
        String asking = serverId
        while (System.currentTimeMillis() < deadline) {
            try {
                Map answer = raftRead(asking, command)
                if (answer.status == 'READ') { return answer }
                asking = answer.leader ?: serverId
            } catch (Exception notAnswering) {
                asking = serverId
            }
            Thread.sleep(100)
        }
        null
    }

    /** Waits until every server given, by default all up, has applied at least this far. Returns whether that happened in time. */
    boolean awaitApplied(String name, long index, long timeoutMillis = 15_000, Collection<String> among = null) {
        waitFor("${name} has applied ${index} everywhere", timeoutMillis) {
            def up = servers(name).findAll { it.alive && (among == null || it.serverId in among) }
            up && up.every { (it.raft?.appliedIndex ?: 0) as long >= index }
        }
    }

    /** Once every server up has applied all it has committed, they report the same checkpoint, which covers what each command's outcome was. */
    boolean checkCheckpointsAgree(String name) {
        Map<String, List<Object>> reported = [:]
        boolean settled = waitFor("${name} has applied all it committed, everywhere", 15_000) {
            def up = servers(name).findAll { it.alive }
            reported = up.collectEntries { [(it.serverId): [it.raft?.appliedIndex, it.raft?.commitIndex, it.raft?.checkpointIndex, it.raft?.checkpointHash]] } as Map<String, List<Object>>
            up && reported.values().every { it[0] == it[1] } && reported.values().collect { it[0] }.unique(false).size() == 1
        }
        if (!check(settled, "every server of ${name} has applied the same entries")) { return false }
        long position = reported.values().first()[2] as long
        check position > 0, "there is a checkpoint to compare (${position})"
        check reported.values().collect { it[2..3] }.unique(false).size() == 1, "every server of ${name} reports the same checkpoint at position ${position}"
    }

    /** Every entry a server holds, committed or not, each with its position. What its snapshot covers is not entries any more. */
    List<Map> raftLog(String serverId) {
        def all = []
        long from = 1
        while (true) {
            List<Map> page = call(serverId, 'raft', [operation: 'log', from: from, limit: 100]).entries as List<Map>
            if (!page) { return all }
            all.addAll(page)
            from = (page.last().index as long) + 1
        }
    }

    /** The server that says it leads in the highest term, among those up and given, or null. A frozen server is not asked, and is not up. */
    String leader(String name, Collection<String> among = null) {
        def leading = servers(name).findAll { it.alive && it.raft?.role == 'LEADER' && (among == null || it.serverId in among) }
        leading ? leading.max { it.raft.term as long }.serverId as String : null
    }

    /** Waits for a leader, returning who, or null. */
    String awaitLeader(String name, long timeoutMillis = 15_000, Collection<String> among = null) {
        String found = null
        waitFor("${name} has a leader", timeoutMillis) { (found = leader(name, among)) != null }
        found
    }

    /** Proposes to whoever leads, retrying as a client must, until it commits or time runs out. Returns the last answer. */
    Map proposeToLeader(String name, Map command, long timeoutMillis = 15_000, Collection<String> among = null) {
        long deadline = System.currentTimeMillis() + timeoutMillis
        Map answer = [status: 'NO_LEADER']
        while (System.currentTimeMillis() < deadline) {
            String current = leader(name, among)
            if (current) {
                try { answer = propose(current, command) }
                catch (Exception notAnswering) { answer = [status: 'NO_ANSWER', reason: notAnswering.message] }
                if (answer.status == 'COMMITTED') { return answer }
            }
            Thread.sleep(100)
        }
        answer
    }

    /** Waits until every server given, by default all up, has committed at least this far. */
    boolean awaitCommitted(String name, long index, long timeoutMillis = 15_000, Collection<String> among = null) {
        waitFor("${name} has committed ${index} everywhere", timeoutMillis) {
            def up = servers(name).findAll { it.alive && (among == null || it.serverId in among) }
            up && up.every { (it.raft?.commitIndex ?: 0) as long >= index }
        }
    }

    /** Two servers saying they lead one term is the one thing Raft must never allow, so scripts look whenever they can. */
    boolean checkOneLeaderPerTerm(String name) {
        def leaders = servers(name).findAll { it.alive && it.raft?.role == 'LEADER' }.groupBy { it.raft.term }
        check leaders.values().every { it.size() == 1 }, "no term of ${name} has two leaders"
    }

    /**
     * Every server up holds the same entries as far as they have all committed, after the furthest snapshot among them. What the snapshots
     * cover is compared by the agreement checkpoints instead: see {@link #checkCheckpointsAgree}.
     */
    boolean checkLogsAgree(String name) {
        def up = servers(name).findAll { it.alive }
        long agreed = up.collect { (it.raft?.commitIndex ?: 0) as long }.min() ?: 0
        long compacted = up.collect { (it.raft?.snapshotIndex ?: 0) as long }.max() ?: 0
        def prefixes = up.collect { raftLog(it.serverId as String).findAll { entry -> (entry.index as long) > compacted && (entry.index as long) <= agreed } }
        check prefixes.toSet().size() == 1, "every server of ${name} holds the same ${Math.max(0L, agreed - compacted)} committed entries" + (compacted ? " after the snapshots, which reach ${compacted}" : '')
    }

    /** Commits one command per position it returns, naming it so that it can be looked for later. Fails a check if one does not commit. */
    Map<Long, String> commitNotes(String name, String label, int count, Collection<String> among = null) {
        def committed = [:] as LinkedHashMap<Long, String>
        count.times { i ->
            String note = key("${label}-${i}")
            Map answer = proposeToLeader(name, kvPut(note, 'noted'), 15_000, among)
            if (check(answer.status == 'COMMITTED', "${note} commits (${answer.status}${answer.reason ? ': ' + answer.reason : ''})")) {
                committed[answer.index as long] = note
            }
        }
        committed
    }

    /** Every server up holds each of these at the position it was committed at. */
    boolean checkHeldEverywhere(String name, Map<Long, String> committed) {
        if (!committed) { return true }
        if (!check(awaitCommitted(name, committed.keySet().max()), "every server of ${name} commits all of them")) { return false }
        boolean all = servers(name).findAll { it.alive }.every { server ->
            def log = raftLog(server.serverId as String).collectEntries { [(it.index as long), it] }
            long compacted = (raftStatus(server.serverId as String).snapshotIndex ?: 0) as long
            // An entry a snapshot covers is not held as an entry: what it did is in the snapshot, which the checkpoints compare.
            committed.every { index, note -> index <= compacted || log[index]?.command?.contains(note) }
        }
        check all, "every server of ${name} holds all ${committed.size()} committed entries where they were committed"
    }

    // ---- A history of what clients saw ------------------------------------------------------------------------------

    /**
     * Runs {@code clients} clients while {@code meanwhile} runs, each reading and writing a few keys and recording what it saw, for
     * {@link HistoryChecker}. {@code write} takes a key and a value that nobody else ever writes, and returns {@code 'ok'}, {@code 'unknown'}
     * or {@code 'no'}, which means it certainly did not take effect. {@code read} takes a key, and returns {@code [value]}, which may hold null,
     * or null if nothing was read. Reading each key once more afterwards, in this same way, lets the history show that nothing was lost.
     */
    List<HistoryOp> clientsDuring(String label, int clients, int keyCount, Closure<String> write, Closure<List> read, Closure meanwhile) {
        def history = Collections.synchronizedList(new ArrayList<HistoryOp>())
        def names = (0..<keyCount).collect { key("${label}-${it}") }
        def stopping = new java.util.concurrent.atomic.AtomicBoolean()
        def workers = (0..<clients).collect { client ->
            Thread.start("client-${client}") {
                def own = new Random()
                int written = 0
                while (!stopping.get()) {
                    String chosen = names[own.nextInt(names.size())]
                    if (own.nextBoolean()) {
                        String value = "${client}.${written++}".toString()
                        recordWrite(history, chosen, value, write)
                    } else {
                        recordRead(history, chosen, read)
                    }
                    Thread.sleep(own.nextInt(20) as long)
                }
            }
        }
        try {
            meanwhile.call()
        } finally {
            stopping.set(true)
            workers*.join()
        }
        names.each { chosen ->
            waitFor("the last read of ${chosen}", 15_000) { recordRead(history, chosen, read) }
        }
        new ArrayList<HistoryOp>(history)
    }

    static void recordWrite(List<HistoryOp> history, String chosen, String value, Closure<String> write) {
        long began = System.nanoTime()
        String outcome
        try { outcome = write.call(chosen, value) }
        catch (Exception noAnswer) { outcome = 'unknown' }
        long ended = System.nanoTime()
        if (outcome == 'ok') { history.add(new HistoryOp(chosen, true, value, began, ended)) }
        else if (outcome == 'unknown') { history.add(new HistoryOp(chosen, true, value, began, null)) }
    }

    static boolean recordRead(List<HistoryOp> history, String chosen, Closure<List> read) {
        long began = System.nanoTime()
        List seen
        try { seen = read.call(chosen) }
        catch (Exception noAnswer) { seen = null }
        long ended = System.nanoTime()
        if (seen == null) { return false }
        history.add(new HistoryOp(chosen, false, seen[0] as String, began, ended))
        true
    }

    /** A Raft swarm's write, as a client makes it: to whoever it last heard leads, by name, or to any server. */
    Closure<String> raftWrites(Collection<String> names) {
        def candidates = new ArrayList<String>(names)
        def leaderNow = new java.util.concurrent.atomic.AtomicReference<String>()
        return { String chosen, String value ->
            String asking = leaderNow.get() ?: candidates[java.util.concurrent.ThreadLocalRandom.current().nextInt(candidates.size())]
            Map answer = propose(asking, kvPut(chosen, value))
            switch (answer.status) {
                case 'COMMITTED': leaderNow.set(asking); return 'ok'
                case 'NOT_LEADER': leaderNow.set(answer.leader as String); return 'no'
                case 'OUTCOME_UNKNOWN': leaderNow.set(null); return 'unknown'
                default: return 'no'
            }
        }
    }

    /** A Raft swarm's linearizable read. */
    Closure<List> raftReads(Collection<String> names) {
        def candidates = new ArrayList<String>(names)
        def leaderNow = new java.util.concurrent.atomic.AtomicReference<String>()
        return { String chosen ->
            String asking = leaderNow.get() ?: candidates[java.util.concurrent.ThreadLocalRandom.current().nextInt(candidates.size())]
            Map answer = raftRead(asking, kvGet(chosen))
            if (answer.status == 'READ') {
                leaderNow.set(asking)
                return answer.result.found ? [answer.result.value] : [null]
            }
            leaderNow.set(answer.status == 'NOT_LEADER' ? answer.leader as String : null)
            return null
        }
    }

    /** Fails a check if some key's history cannot be explained, showing the start of the first that cannot. */
    boolean checkLinearizable(List<HistoryOp> history, String what) {
        def result = HistoryChecker.check(history)
        log "History of ${what}: ${result.operations} operations on ${result.keys} keys, ${history.count { it.write }} writes, ${history.count { it.write && !it.definite }} of unknown outcome"
        result.violations.keySet().take(1).each { log "No order explains ${it}" }
        result.undecided.each { log "Gave up on ${it}" }
        check result.linearizable, "${what} is linearizable"
    }

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
        mark "experiment: ${title}"
        if (!check(parts.steady.call() as boolean, "steady state holds before: ${title}")) {
            log "=== Aborted: the swarm was not steady to begin with"
            return false
        }
        boolean ok = false
        try {
            mark "breaking it: ${title}"
            parts.method.call()
            mark "checking it recovered: ${title}"
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
        mark "${ok ? 'held' : 'broke'}: ${title}"
        ok
    }

    /** Notes a step on the page's timeline, so a replay shows what was done when. An older harness does not have one, which is fine. */
    void mark(String text) {
        try { request('POST', '/api/traffic/mark', [text: text.toString()]) }
        catch (Exception ignored) { /* A step goes unmarked; the script carries on. */ }
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
        links.computeIfAbsent("${port}/${Thread.currentThread().threadId()}".toString()) { new ServerLink('localhost', port) }
    }

    private synchronized void closeLink(String serverId) {
        def port = ports[serverId]
        if (port == null) { return }
        def closing = links.keySet().findAll { it.startsWith("${port}/") }
        closing.each { links.remove(it)?.close() }
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
