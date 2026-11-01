// What consensus buys, side by side: one split, at one moment, on a swarm whose kv replicates by itself with the newest write winning, and on a
// Raft swarm. Clients read and write on both throughout, and each is asked to take the same two writes to one key, one on each side of the split.
// On last-writer-wins both sides take them, and after healing one is gone, though its client was told it worked. On Raft the smaller side refuses,
// and nothing acknowledged is lost. Open the page while it runs, to watch the two together.
// It leaves its swarms running to look at, unless given a third argument. It needs gossip, kv and Raft in the harness, and --kv-replicate.
// Usage: swarm compare-split.groovy [servers] [clients] [stop]
@groovy.transform.BaseScript SwarmScript swarm

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

int size = args ? args[0] as int : 5
int clients = args && args.size() > 1 ? args[1] as int : 4
boolean stopAtEnd = args && args.size() > 2
String lww = 'lww'
String raft = 'raft'

snapshot().swarms.findAll { it.name in [lww, raft] }.each { stopSwarm it.name as String }
def lwwServers = start(lww, size)
def raftServers = startRaft(raft, size)

// Only what happens once the split has taken hold is counted, as a request already sent still gets its answer.
def splitting = new AtomicBoolean()
def counts = new ConcurrentHashMap<String, AtomicInteger>()
def count = { String what -> if (splitting.get()) { counts.computeIfAbsent(what) { new AtomicInteger() }.incrementAndGet() } }
def counted = { String what -> counts[what]?.get() ?: 0 }

int smallerSize = (int) (size - 1).intdiv(2)
List<String> lwwSmaller = lwwServers.take(smallerSize)
List<String> lwwLarger = lwwServers.drop(smallerSize)

def lwwWrite = { String chosen, String value ->
    String server = lwwServers[ThreadLocalRandom.current().nextInt(lwwServers.size())]
    put(server, chosen, value)
    count("lww ${server in lwwSmaller ? 'smaller' : 'larger'}".toString())
    'ok'
}
def lwwRead = { String chosen ->
    def found = kv(lwwServers[ThreadLocalRandom.current().nextInt(lwwServers.size())], [operation: 'get', key: chosen])
    found.found ? [found.value] : [null]
}
def raftWrite = raftWrites(raftServers)
def countedRaftWrite = { String chosen, String value ->
    String outcome = 'unknown'
    try { outcome = raftWrite.call(chosen, value) }
    finally { count("raft ${outcome}".toString()) }
    outcome
}

String shared = key('shared')
String largerValue = 'from the larger side'
String smallerValue = 'from the smaller side'
boolean lwwSmallerTook = false, lwwLargerTook = false
Map raftLargerAnswer = [:], raftOldLeaderAnswer = [:], raftOldLeaderRead = [:]
String lwwSeenOnLarger = null, lwwSeenOnSmaller = null
String raftOld = null
List<String> raftSmaller = []
long commitAtSplit = 0
long commitAfterSplit = -1

List<HistoryOp> lwwHistory = []
List<HistoryOp> raftHistory = []
try {
    check kvStatus(lwwServers.first()).replication == 'on', 'the first swarm replicates by itself, as it needs --kv-replicate for'
    check awaitInSync(lww), "${size} servers of ${lww} are in sync"
    check awaitLeader(raft) != null, "${size} servers of ${raft} elect a leader"

    lwwHistory = clientsDuring('split', clients, 3, lwwWrite, lwwRead) {
        raftHistory = clientsDuring('split', clients, 3, countedRaftWrite, raftReads(raftServers)) {
            pause 2_000
            raftOld = leader(raft)
            raftSmaller = [raftOld] + raftServers.findAll { it != raftOld }.take(smallerSize - 1)
            def raftLarger = raftServers - raftSmaller
            mark 'the split: both swarms at once'
            log "Splitting ${lwwSmaller} from ${lwwLarger}, and ${raftSmaller} from ${raftLarger}, with the Raft leader ${raftOld} on the smaller side"
            partition lwwSmaller + raftSmaller, lwwLarger + raftLarger
            pause 500
            commitAtSplit = raftStatus(raftOld).commitIndex as long
            splitting.set(true)

            // The same two writes to one key on each swarm: the larger side first, then the smaller.
            mark 'the same two writes to one key, on each swarm'
            put(lwwLarger.first(), shared, largerValue)
            lwwLargerTook = true
            pause 50
            put(lwwSmaller.first(), shared, smallerValue)
            lwwSmallerTook = true
            pause 500
            lwwSeenOnLarger = get(lwwLarger.first(), shared)
            lwwSeenOnSmaller = get(lwwSmaller.first(), shared)

            String raftNext = awaitLeader(raft, 15_000, raftLarger)
            raftLargerAnswer = proposeToLeader(raft, kvPut(shared, largerValue), 15_000, raftLarger)
            raftOldLeaderAnswer = propose(raftOld, kvPut(shared, smallerValue))
            raftOldLeaderRead = raftRead(raftOld, kvGet(shared))
            log "On Raft, ${raftNext} leads the larger side, and ${raftOld} still believes it leads the smaller"

            pause 2_000
            commitAfterSplit = raftStatus(raftOld).commitIndex as long
            splitting.set(false)
            mark 'healing the split'
            heal()
            pause 3_000
        }
    }
    mark 'healed: what each swarm kept'

    check lwwLargerTook && lwwSmallerTook, 'on last-writer-wins, both sides acknowledged a write to one key'
    check lwwSeenOnLarger == largerValue && lwwSeenOnSmaller == smallerValue, 'and while split, the two sides gave different answers for it'
    check counted('lww smaller') > 0 && counted('lww larger') > 0, 'and its clients were acknowledged on both sides'
    check awaitInSync(lww), 'after healing, last-writer-wins is in sync again'
    def lwwKept = lwwServers.collect { get(it, shared) }.unique(false)
    check lwwKept == [smallerValue], "and every server holds only the later write (${lwwKept}), so the larger side's acknowledged write is gone"
    def lwwResult = HistoryChecker.check(lwwHistory)
    check !lwwResult.violations.isEmpty(), "the history checker finds what clients of last-writer-wins saw cannot be explained (${lwwResult.violations.size()} of ${lwwResult.keys} keys)"

    check raftLargerAnswer.status == 'COMMITTED', "on Raft, the larger side took its write (${raftLargerAnswer.status})"
    check raftOldLeaderAnswer.status == 'OUTCOME_UNKNOWN', "the old leader on the smaller side could not acknowledge its write (${raftOldLeaderAnswer.status})"
    check raftOldLeaderRead.status != 'READ', "and would not answer a read (${raftOldLeaderRead.status})"
    check commitAfterSplit == commitAtSplit, 'the smaller side committed nothing while split'
    check counted('raft ok') > 0, 'and clients were still acknowledged, by the larger side'
    check awaitApplied(raft, raftLargerAnswer.index as long), 'after healing, every Raft server has applied what was acknowledged'
    def raftKept = raftServers.collect { get(it, shared) }.unique(false)
    check raftKept == [largerValue], "and every server holds what was acknowledged (${raftKept}), and not what was refused"
    checkLinearizable(raftHistory, 'what clients of Raft saw')
    checkOneLeaderPerTerm(raft)
    checkLogsAgree(raft)
    checkCheckpointsAgree(raft)

    def row = { String what, Object left, Object right -> log "${what.padRight(44)} ${left.toString().padRight(30)} ${right}" }
    def raftResult = HistoryChecker.check(raftHistory)
    log '=== What each did through one split'
    row '', 'last-writer-wins', 'Raft'
    row 'Writes acknowledged on the smaller side', counted('lww smaller'), 'none: it committed nothing'
    row 'Writes acknowledged on the larger side', counted('lww larger'), counted('raft ok')
    row 'Writes not acknowledged', 0, counted('raft no') + counted('raft unknown')
    row 'One key, written once on each side', 'both acknowledged', "${raftLargerAnswer.status}, and ${raftOldLeaderAnswer.status}"
    row 'That key while split', 'two different answers', 'one answer, or none'
    row 'That key after healing', "\"${lwwKept.join(', ')}\"", "\"${raftKept.join(', ')}\""
    row 'An acknowledged write lost', 'yes', 'no'
    row 'Keys no order of events explains', "${lwwResult.violations.size()} of ${lwwResult.keys}", "${raftResult.violations.size()} of ${raftResult.keys}"
    log 'Raft acknowledges few while split because a client that asks the old leader waits out propose.timeout.millis, and these clients keep finding it. Phase 6 of the plan ends that.'
} finally {
    heal()
    if (stopAtEnd) {
        stopSwarm lww
        stopSwarm raft
    }
}
