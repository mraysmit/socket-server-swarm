// Chaos: a follower cut off from every other server for a minute, then brought back. With pre-vote it never wins a pre-vote while away, so
// it never enters a new term, and when it returns the leader carries on undisturbed in the same term. Without pre-vote its term would climb
// with every election it tried, and its return would force an election.
// It starts its own swarm, and stops it at the end. It needs the Raft plugin in the harness's --plugins-dir.
// Usage: swarm chaos/raft-isolated-rejoin.groovy [swarm] [servers] [seconds]
@groovy.transform.BaseScript SwarmScript swarm

String name = args ? args[0] : 'raftrejoin'
int size = args && args.size() > 1 ? args[1] as int : 5
int seconds = args && args.size() > 2 ? args[2] as int : 60

def everyone = startRaft(name, size)
try {
    String leading = awaitLeader(name)
    check leading != null, "${size} servers elect a leader"
    long term = raftStatus(leading).term as long
    long changesBefore = raftStatus(leading).leaderChanges as long
    String away = everyone.find { it != leading }
    def committed = [:] as LinkedHashMap<Long, String>
    experiment "a server cut off for ${seconds} s rejoins without disturbing the leader", [
        steady: { checkOneLeaderPerTerm(name) && leader(name) != null },
        method: {
            isolate away
            long until = System.currentTimeMillis() + seconds * 1_000L
            int round = 0
            while (System.currentTimeMillis() < until) {
                committed.putAll commitNotes(name, "while-away-${round++}", 1, everyone - away)
                pause 5_000
            }
            Map alone = raftStatus(away)
            check alone.term as long == term, "${away} is still in term ${term} after ${seconds} s alone (${alone.term})"
            check alone.role != 'LEADER', "${away} never came to lead (${alone.role})"

            heal()
            checkHeldEverywhere(name, committed)
            check leader(name) == leading, "${leading} still leads (${leader(name)})"
            def terms = servers(name).collect { it.raft?.term }
            check terms.every { it as long == term }, "every server is still in term ${term} (${terms})"
            check raftStatus(leading).leaderChanges as long == changesBefore, 'the leader saw no change of leader'
            checkLogsAgree(name)
        },
        rollback: { heal() }
    ]
} finally {
    heal()
    stopSwarm name
}
