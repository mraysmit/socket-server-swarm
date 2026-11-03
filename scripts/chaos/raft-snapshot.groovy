// Chaos: logs compacted into snapshots, with the real kv. The harness has every server take a snapshot each 32 entries applied, so:
//  1. A follower down while more than that was committed comes back to find the entries it needs compacted away, and is sent a snapshot.
//  2. Every server stopped and started again rebuilds kv, which keeps nothing itself, from its own snapshot and the log after it.
//  3. A fresh server added once the log is compacted is caught up by a snapshot before it gets a vote.
// After each, every server holds the same kv values, reports the same agreement checkpoint, and holds the same log after its snapshot.
// It starts its own swarm, and stops it at the end. It needs the Raft and kv plugins in the harness's --plugins-dir.
// Usage: swarm chaos/raft-snapshot.groovy [swarm] [servers]
@groovy.transform.BaseScript SwarmScript swarm

String name = args ? args[0] : 'raftsnap'
int size = args && args.size() > 1 ? args[1] as int : 3

long snapshotOf(String serverId) { (raftStatus(serverId).snapshotIndex ?: 0) as long }

/** Every server up holds the value each note was given, which kv could only have from Raft, through the log or a snapshot. */
boolean checkKvHolds(String name, Map<Long, String> committed) {
    boolean all = waitFor("every server of ${name} holds all ${committed.size()} values", 15_000) {
        alive(name).every { server -> committed.values().every { note -> get(server, note) == 'noted' } }
    }
    check all, "every server of ${name} holds the value of all ${committed.size()} keys written"
}

startRaft(name, size)
try {
    check awaitLeader(name) != null, "${size} servers elect a leader"
    def committed = commitNotes(name, 'before', 3)

    experiment 'a follower that missed what was compacted is sent a snapshot', [
        steady: { checkOneLeaderPerTerm(name) && awaitLeader(name) != null },
        method: {
            String down = (alive(name) - leader(name)).first()
            kill down
            committed.putAll commitNotes(name, 'while-down', 40)
            String leading = leader(name)
            check waitFor("${leading} compacted its log", 15_000) { snapshotOf(leading) > 0 }, "the leader compacted its log (up to ${snapshotOf(leading)})"
            restart down
            check waitFor("${down} installed a snapshot", 15_000) { snapshotOf(down) > 0 }, "${down} was sent a snapshot (up to ${snapshotOf(down)})"
            checkHeldEverywhere(name, committed)
            checkKvHolds(name, committed)
            checkCheckpointsAgree(name)
            checkLogsAgree(name)
        }
    ]

    experiment 'every server restarted rebuilds kv from its snapshot and the log after it', [
        steady: { checkOneLeaderPerTerm(name) && awaitLeader(name) != null },
        method: {
            def everyone = alive(name)
            everyone.each { kill it }
            everyone.each { restart it }
            check awaitLeader(name) != null, 'they elect a leader again'
            check everyone.every { snapshotOf(it) > 0 }, "every server started from a snapshot (${everyone.collect { snapshotOf(it) }})"
            committed.putAll commitNotes(name, 'after-restart', 3)
            checkKvHolds(name, committed)
            checkCheckpointsAgree(name)
            checkLogsAgree(name)
        }
    ]

    experiment 'a fresh server added once the log is compacted is caught up by a snapshot', [
        steady: { checkOneLeaderPerTerm(name) && awaitLeader(name) != null },
        method: {
            def added = add(name, 1)
            check added.size() == 1 && snapshotOf(added.first()) > 0, "${added} was caught up by a snapshot (up to ${added ? snapshotOf(added.first()) : 0})"
            committed.putAll commitNotes(name, 'after-adding', 3)
            checkKvHolds(name, committed)
            checkCheckpointsAgree(name)
            checkLogsAgree(name)
        }
    ]
} finally {
    stopSwarm name
}
