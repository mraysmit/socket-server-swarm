// Chaos: freeze the leader of a Raft swarm, as a long GC pause would, for longer than an election takes.
// The others must replace it and carry on. Thawed, it wakes up believing it still leads: it must step down, commit nothing of its own,
// and catch up. This is the case a timeout cannot tell from a crash, so it is the one a leader lease would get wrong.
// It starts its own swarm, and stops it at the end. It needs the Raft plugin in the harness's --plugins-dir.
// Usage: swarm chaos/raft-frozen-leader.groovy [swarm] [servers]
@groovy.transform.BaseScript SwarmScript swarm

String name = args ? args[0] : 'raftfreeze'
int size = args && args.size() > 1 ? args[1] as int : 3

startRaft name, size
def committed = [:] as LinkedHashMap<Long, String>
try {
    check awaitLeader(name) != null, "${size} servers elect a leader"
    committed.putAll commitNotes(name, 'before', 3)
    experiment 'a frozen leader is replaced, and steps down when thawed', [
        steady: { checkOneLeaderPerTerm(name) && awaitLeader(name) != null },
        method: {
            String old = leader(name)
            long oldTerm = raftStatus(old).term as long
            def others = servers(name)*.serverId.findAll { it != old }
            freeze old
            String next = awaitLeader(name, 15_000, others)
            check next != null && next != old, "another server takes over from frozen ${old} (${next})"
            check raftStatus(next).term as long > oldTerm, 'in a later term'
            committed.putAll commitNotes(name, 'while-frozen', 3, others)
            thaw old
            check waitFor("${old} steps down", 15_000) { raftStatus(old).role == 'FOLLOWER' }, "${old} steps down once thawed"
            checkOneLeaderPerTerm(name)
            checkHeldEverywhere(name, committed)
            checkLogsAgree(name)
        }
    ]
} finally {
    stopSwarm name
}
