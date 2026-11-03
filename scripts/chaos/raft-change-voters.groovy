// Chaos: changing a Raft swarm's voters while things go wrong, one server at a time and always through the log.
//  1. A voter crashes and is replaced: it is removed while down, by the others, and a fresh server is added in its place.
//  2. The swarm splits, and the larger side adds a server and removes one cut off on the smaller side, which commits nothing meanwhile.
//  3. The leader is removed while clients write: it hands over, and nothing it committed is lost.
// After each, one leader per term, every server holds the same committed log, and every server has the same voters.
// It starts its own swarm, and stops it at the end. It needs the Raft and kv plugins in the harness's --plugins-dir.
// Usage: swarm chaos/raft-change-voters.groovy [swarm] [servers]
@groovy.transform.BaseScript SwarmScript swarm

String name = args ? args[0] : 'raftvoters'
int size = args && args.size() > 1 ? args[1] as int : 5

List<String> votersOf(String serverId) { (raftStatus(serverId).voters ?: []) as List<String> }

/** Every server of the swarm that is up names exactly the servers listed, and nothing more. */
boolean checkVotersAreTheSwarm(String name) {
    def listed = servers(name)*.serverId as List<String>
    boolean agreed = waitFor("every server of ${name} has ${listed.size()} voters", 15_000) {
        alive(name).every { votersOf(it).sort() == listed.sort() }
    }
    check agreed, "every server of ${name} names the ${listed.size()} servers listed as its voters, and no others"
}

startRaft(name, size)
try {
    check awaitLeader(name) != null, "${size} servers elect a leader"
    def committed = commitNotes(name, 'before', 3)

    experiment 'a voter that crashed is replaced by a fresh server', [
        steady: { checkOneLeaderPerTerm(name) && awaitLeader(name) != null },
        method: {
            String down = (alive(name) - leader(name)).first()
            kill down
            remove down
            check !(down in votersOf(leader(name))), "${down} is no longer a voter, though it was down when it was removed"
            def fresh = add(name, 1)
            check fresh.size() == 1 && fresh.first() in votersOf(leader(name)), "a fresh server, ${fresh}, took its place as a voter"
            committed.putAll commitNotes(name, 'replaced', 3)
            checkHeldEverywhere(name, committed)
            checkVotersAreTheSwarm(name)
            checkLogsAgree(name)
        }
    ]

    experiment 'the larger side of a split changes the voters, and the smaller side commits nothing', [
        steady: { checkOneLeaderPerTerm(name) && awaitLeader(name) != null },
        method: {
            def everyone = alive(name)
            String old = leader(name)
            def larger = [old] + (everyone - old).take(everyone.size().intdiv(2))
            def smaller = everyone - larger
            partition larger, smaller
            def stuck = smaller.drop(1)
            def stuckAt = stuck.collectEntries { [it, raftStatus(it).commitIndex as long] }
            check awaitLeader(name, 15_000, larger) != null, "the larger side ${larger} leads"
            def added = add(name, 1)
            check added.size() == 1, "the larger side adds ${added}"
            String cut = smaller.first()
            remove cut
            check !(cut in votersOf(leader(name, larger))), "and removes ${cut}, cut off on the smaller side"
            committed.putAll commitNotes(name, 'split', 3, larger + added)
            check stuck.every { raftStatus(it).role != 'LEADER' && raftStatus(it).commitIndex as long == stuckAt[it] },
                    "the rest of the smaller side, ${stuck}, has no leader and committed nothing meanwhile"
            heal()
            checkHeldEverywhere(name, committed)
            checkVotersAreTheSwarm(name)
            checkOneLeaderPerTerm(name)
            checkLogsAgree(name)
        },
        rollback: { heal() }
    ]

    experiment 'the leader removed while clients write hands over and loses nothing', [
        steady: { checkOneLeaderPerTerm(name) && awaitLeader(name) != null },
        method: {
            String old = leader(name)
            def written = Collections.synchronizedMap(new LinkedHashMap<Long, String>())
            def stopping = new java.util.concurrent.atomic.AtomicBoolean()
            def writer = Thread.start {
                int i = 0
                while (!stopping.get()) {
                    String note = key("during-${i++}")
                    try {
                        Map answer = proposeToLeader(name, kvPut(note, 'noted'), 5_000)
                        if (answer.status == 'COMMITTED') { written[answer.index as long] = note }
                    } catch (Exception notNow) { /* a leader may be stepping down: the next one is asked */ }
                }
            }
            pause 300
            remove old
            String next = awaitLeader(name)
            check next != null && next != old, "another server leads (${next})"
            pause 500
            stopping.set(true)
            writer.join(15_000)
            check written.size() > 0, "clients' writes committed through the change (${written.size()})"
            committed.putAll(written)
            checkHeldEverywhere(name, committed)
            checkVotersAreTheSwarm(name)
            checkOneLeaderPerTerm(name)
            checkLogsAgree(name)
        }
    ]
} finally {
    heal()
    stopSwarm name
}
