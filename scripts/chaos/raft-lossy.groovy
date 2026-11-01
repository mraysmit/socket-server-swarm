// Chaos: a slow network everywhere, and heavy loss at one server.
// Commits slow down, and the lossy server falls behind or loses the lead, but nothing committed is lost, and agreement never breaks.
// It starts its own swarm, and stops it at the end. It needs the Raft plugin in the harness's --plugins-dir.
// Usage: swarm chaos/raft-lossy.groovy [swarm] [servers] [delayMillis] [lossPercent]
@groovy.transform.BaseScript SwarmScript swarm

String name = args ? args[0] : 'raftlossy'
int size = args && args.size() > 1 ? args[1] as int : 5
long delayMillis = args && args.size() > 2 ? args[2] as long : 30
int lossPercent = args && args.size() > 3 ? args[3] as int : 40

startRaft name, size
def committed = [:] as LinkedHashMap<Long, String>
try {
    check awaitLeader(name) != null, "${size} servers elect a leader"
    committed.putAll commitNotes(name, 'before', 3)
    experiment "${delayMillis} ms of delay everywhere and ${lossPercent}% loss at one server", [
        steady: { checkOneLeaderPerTerm(name) && awaitLeader(name) != null },
        method: {
            def everyone = servers(name)*.serverId as List<String>
            delay everyone, delayMillis
            loss([leader(name)], lossPercent)
            committed.putAll commitNotes(name, 'lossy', 10)
            checkOneLeaderPerTerm(name)
            heal()
            checkHeldEverywhere(name, committed)
            checkLogsAgree(name)
        },
        rollback: { heal() }
    ]
} finally {
    stopSwarm name
}
