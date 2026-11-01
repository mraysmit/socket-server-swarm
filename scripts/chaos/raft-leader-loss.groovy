// Chaos: stop the leader of a Raft swarm, again and again.
// A new leader must take over within the target, nothing committed may be lost, and the old leader must rejoin as a follower.
// It starts its own swarm, and stops it at the end. It needs the Raft plugin in the harness's --plugins-dir.
// Usage: swarm chaos/raft-leader-loss.groovy [swarm] [servers] [rounds] [targetMillis]
@groovy.transform.BaseScript SwarmScript swarm

String name = args ? args[0] : 'raftloss'
int size = args && args.size() > 1 ? args[1] as int : 5
int rounds = args && args.size() > 2 ? args[2] as int : 3
long target = args && args.size() > 3 ? args[3] as long : 3_000

startRaft name, size
def committed = [:] as LinkedHashMap<Long, String>
def failovers = []
try {
    check awaitLeader(name) != null, "${size} servers elect a leader"
    committed.putAll commitNotes(name, 'before', 3)
    rounds.times { round ->
        experiment "losing leader ${round + 1} of ${rounds}", [
            steady: { checkOneLeaderPerTerm(name) && awaitLeader(name) != null },
            method: {
                String old = leader(name)
                def others = servers(name)*.serverId.findAll { it != old }
                kill old
                long stopped = System.currentTimeMillis()
                // Measured to the first commit, because that is when clients can carry on, and a leader alone proves nothing.
                Map first = proposeToLeader(name, kvPut(key("failover-${round}"), 'noted'), 15_000, others)
                long took = System.currentTimeMillis() - stopped
                failovers << took
                log "From stopping ${old} to the next commit: ${took} ms"
                if (check(first.status == 'COMMITTED', "a new leader commits after ${old} stops (${first.status})")) { committed[first.index as long] = key("failover-${round}") }
                check took <= target, "that took ${took} ms, within the target of ${target} ms"
                committed.putAll commitNotes(name, "without-${round}", 2, others)
                restart old
                check waitFor("${old} follows again", 15_000) { raftStatus(old).role == 'FOLLOWER' && raftStatus(old).leader != null }, "${old} rejoins as a follower"
                checkHeldEverywhere(name, committed)
                checkLogsAgree(name)
            }
        ]
    }
    log "Times from losing a leader to the next commit: ${failovers} ms"
} finally {
    stopSwarm name
}
