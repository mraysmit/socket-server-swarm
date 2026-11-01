// Chaos: split a Raft swarm with its leader on the smaller side.
// That side must commit nothing, however long it believes its leader leads: this is what kv's replication, which takes writes on both
// sides, does not promise. The larger side must elect a leader and carry on. After healing, everyone must agree, and what the old leader
// accepted but could not commit must be gone.
// It starts its own swarm, and stops it at the end. It needs the Raft plugin in the harness's --plugins-dir.
// Usage: swarm chaos/raft-partition.groovy [swarm] [servers]
@groovy.transform.BaseScript SwarmScript swarm

String name = args ? args[0] : 'raftsplit'
int size = args && args.size() > 1 ? args[1] as int : 5

startRaft name, size
def committed = [:] as LinkedHashMap<Long, String>
try {
    check awaitLeader(name) != null, "${size} servers elect a leader"
    committed.putAll commitNotes(name, 'before', 3)
    experiment 'only the larger side of a split commits', [
        steady: { checkOneLeaderPerTerm(name) && awaitLeader(name) != null },
        method: {
            String old = leader(name)
            def everyone = servers(name)*.serverId as List<String>
            def smaller = [old] + everyone.findAll { it != old }.take((int) (size - 1).intdiv(2) - 1)
            def larger = everyone - smaller
            log "Splitting ${smaller} from ${larger}, with leader ${old} on the smaller side"
            partition smaller, larger
            long commitBefore = raftStatus(old).commitIndex as long
            String lost = key('never-commits')
            Map refused = propose(old, kvPut(lost, 'noted'))
            check refused.status == 'OUTCOME_UNKNOWN', "the old leader cannot commit, and says it cannot know (${refused.status})"
            check raftStatus(old).commitIndex as long == commitBefore, 'the smaller side has committed nothing more'

            String next = awaitLeader(name, 15_000, larger)
            check next != null, "the larger side elects a leader (${next})"
            committed.putAll commitNotes(name, 'larger-side', 3, larger)
            check smaller.every { raftStatus(it).commitIndex as long == commitBefore }, 'the smaller side has still committed nothing more'

            heal()
            checkHeldEverywhere(name, committed)
            checkLogsAgree(name)
            check waitFor('the old leader has given up what it could not commit', 15_000) { raftLog(old).every { !it.command?.contains(lost) } }, 'what the smaller side accepted is gone from it'
            checkOneLeaderPerTerm(name)
        },
        rollback: { heal() }
    ]
} finally {
    stopSwarm name
}
