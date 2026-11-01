// Chaos: stop every server of a Raft swarm, then start them all again.
// Everything committed must still be there, each server must remember its term and vote, and a leader must be elected without being told
// anything: the cluster is saved on each server.
// It starts its own swarm, and stops it at the end. It needs the Raft plugin in the harness's --plugins-dir.
// Usage: swarm chaos/raft-restart-all.groovy [swarm] [servers]
@groovy.transform.BaseScript SwarmScript swarm

String name = args ? args[0] : 'raftrestart'
int size = args && args.size() > 1 ? args[1] as int : 3

startRaft name, size
def committed = [:] as LinkedHashMap<Long, String>
try {
    check awaitLeader(name) != null, "${size} servers elect a leader"
    committed.putAll commitNotes(name, 'before', 5)
    experiment 'every server stopped and restarted loses nothing', [
        steady: { checkOneLeaderPerTerm(name) && awaitLeader(name) != null },
        method: {
            def everyone = servers(name)*.serverId as List<String>
            long termBefore = everyone.collect { raftStatus(it).term as long }.max()
            everyone.each { kill it }
            check !servers(name).any { it.alive }, 'every server is down'
            everyone.shuffled().each { restart it }
            String next = awaitLeader(name)
            check next != null, "a leader is elected with no bootstrap (${next})"
            check raftStatus(next).term as long > termBefore, 'in a later term than any before'
            committed.putAll commitNotes(name, 'after', 2)
            checkHeldEverywhere(name, committed)
            checkLogsAgree(name)
        }
    ]
} finally {
    stopSwarm name
}
