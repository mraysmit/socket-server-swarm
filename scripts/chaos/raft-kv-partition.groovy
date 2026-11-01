// Chaos: kv on a Raft swarm, split with its leader on the smaller side, while clients read and write throughout.
// The smaller side must refuse: it can take no write and answer no read. What clients saw must be explained by one copy of the data, so no
// acknowledged write is lost, and no read is stale, which the history checker decides. chaos/history-checker.groovy shows it can find both.
// It starts its own swarm, and stops it at the end. It needs the Raft and kv plugins in the harness's --plugins-dir.
// Usage: swarm chaos/raft-kv-partition.groovy [swarm] [servers] [clients]
@groovy.transform.BaseScript SwarmScript swarm

String name = args ? args[0] : 'raftkvsplit'
int size = args && args.size() > 1 ? args[1] as int : 5
int clients = args && args.size() > 2 ? args[2] as int : 4

def everyone = startRaft(name, size)
try {
    check awaitLeader(name) != null, "${size} servers elect a leader"
    long acknowledged = 0
    experiment 'the smaller side refuses, and no acknowledged write is lost', [
        steady: { checkOneLeaderPerTerm(name) && awaitLeader(name) != null },
        method: {
            def history = clientsDuring('split', clients, 3, raftWrites(everyone), raftReads(everyone)) {
                pause 2_000
                String old = leader(name)
                def smaller = [old] + everyone.findAll { it != old }.take((int) (size - 1).intdiv(2) - 1)
                def larger = everyone - smaller
                log "Splitting ${smaller} from ${larger}, with leader ${old} on the smaller side"
                partition smaller, larger
                // A request already sent still gets its answer, so what is on its way may yet commit.
                pause 500

                long commitBefore = raftStatus(old).commitIndex as long
                String refused = key('refused')
                Map write = propose(old, kvPut(refused, 'on the smaller side'))
                check write.status == 'OUTCOME_UNKNOWN', "the old leader takes no write (${write.status})"
                Map read = raftRead(old, kvGet(key('split-0')))
                check read.status != 'READ', "the old leader answers no read (${read.status})"
                smaller.findAll { it != old }.each {
                    check propose(it, kvPut(refused, 'there too')).status != 'COMMITTED', "${it} takes no write either"
                }
                check raftStatus(old).commitIndex as long == commitBefore, 'the smaller side has committed nothing more'

                String next = awaitLeader(name, 15_000, larger)
                check next != null, "the larger side elects a leader (${next})"
                Map carried = proposeToLeader(name, kvPut(key('larger'), 'carried on'), 15_000, larger)
                check carried.status == 'COMMITTED', "the larger side takes writes (${carried.status})"
                acknowledged = carried.index as long
                pause 3_000
                check smaller.every { raftStatus(it).commitIndex as long == commitBefore }, 'the smaller side has still committed nothing more'

                heal()
                pause 3_000
            }
            checkOneLeaderPerTerm(name)
            check history.count { it.write && it.definite } > 20, 'clients got enough writes acknowledged for this to mean something'
            check history.count { !it.write && it.value != null } > 20, 'clients read enough back for this to mean something'
            checkLinearizable(history, 'what clients saw through the split')

            check awaitApplied(name, acknowledged), 'every server has applied what was acknowledged'
            everyone.each { check get(it, key('larger')) == 'carried on', "${it} holds what the larger side took" }
            check everyone.every { get(it, key('refused')) != 'on the smaller side' }, 'what the smaller side was asked to take is nowhere'
            checkLogsAgree(name)
            checkCheckpointsAgree(name)
        },
        rollback: { heal() }
    ]
} finally {
    stopSwarm name
}
