// Chaos: the leader cut off from every other server. With check-quorum it steps down once it has heard from no majority for
// election.timeout.max.millis, by itself and without hearing of a newer term, so a client that asks it is told at once that it does not
// lead, instead of waiting until its request times out. The others elect a leader and carry on, and when it returns it follows.
// It starts its own swarm, and stops it at the end. It needs the Raft and kv plugins in the harness's --plugins-dir.
// Usage: swarm chaos/raft-cut-off-leader.groovy [swarm] [servers]
@groovy.transform.BaseScript SwarmScript swarm

String name = args ? args[0] : 'raftcutoff'
int size = args && args.size() > 1 ? args[1] as int : 5
long bound = 3_000

def everyone = startRaft(name, size)
try {
    check awaitLeader(name) != null, "${size} servers elect a leader"
    def committed = commitNotes(name, 'before', 3)
    experiment 'a leader cut off steps down, and the others carry on', [
        steady: { checkOneLeaderPerTerm(name) && awaitLeader(name) != null },
        method: {
            String old = leader(name)
            long term = raftStatus(old).term as long
            def others = everyone - old
            isolate old
            long began = System.currentTimeMillis()
            long tookMillis = -1
            for (long waited = 0; waited < 10_000; waited = System.currentTimeMillis() - began) {
                if (raftStatus(old).role != 'LEADER') { tookMillis = waited; break }
                pause 20
            }
            check tookMillis >= 0 && tookMillis <= bound, "${old} stopped leading within ${bound} ms of being cut off (${tookMillis} ms)"
            check raftStatus(old).term as long == term, "by itself: it was told of no newer term, and is still in term ${term}"

            long asked = System.currentTimeMillis()
            Map write = propose(old, kvPut(key('to-the-old-leader'), 'refused'))
            long writeMillis = System.currentTimeMillis() - asked
            check write.status == 'NOT_LEADER' && writeMillis < 1_000, "a write sent to it is refused at once (${write.status}, ${writeMillis} ms)"
            asked = System.currentTimeMillis()
            Map read = raftRead(old, kvGet(key('before-0')))
            long readMillis = System.currentTimeMillis() - asked
            check read.status == 'NOT_LEADER' && readMillis < 1_000, "and so is a read (${read.status}, ${readMillis} ms)"

            String next = awaitLeader(name, 15_000, others)
            check next != null, "the others elect a leader (${next})"
            committed.putAll commitNotes(name, 'after', 3, others)

            heal()
            checkHeldEverywhere(name, committed)
            check waitFor("${old} follows ${next}", 15_000) { Map now = raftStatus(old); now.role == 'FOLLOWER' && now.leader == leader(name) },
                    "${old} follows when it returns"
            checkOneLeaderPerTerm(name)
            checkLogsAgree(name)
        },
        rollback: { heal() }
    ]
} finally {
    heal()
    stopSwarm name
}
