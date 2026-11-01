// Chaos: stop every server of a Raft swarm running kv, and start them all again.
// kv keeps nothing: its store is in memory. All its data must still return, because Raft replays the whole log into it.
// It starts its own swarm, and stops it at the end. It needs the Raft and kv plugins in the harness's --plugins-dir.
// Usage: swarm chaos/raft-kv-restart-all.groovy [swarm] [servers] [pairs]
@groovy.transform.BaseScript SwarmScript swarm

String name = args ? args[0] : 'raftkvrestart'
int size = args && args.size() > 1 ? args[1] as int : 3
int pairs = args && args.size() > 2 ? args[2] as int : 20

def everyone = startRaft(name, size)
try {
    check awaitLeader(name) != null, "${size} servers elect a leader"
    def written = (0..<pairs).collectEntries { [(key("pair-${it}")): "value-${it}".toString()] }
    long last = 0
    written.each { pair, value -> last = proposeToLeader(name, kvPut(pair, value)).index as long }
    check awaitApplied(name, last), "all ${pairs} pairs are applied"
    experiment 'every server stopped and restarted returns all its data', [
        steady: { checkOneLeaderPerTerm(name) && awaitLeader(name) != null },
        method: {
            everyone.each { kill it }
            check !servers(name).any { it.alive }, 'every server is down'
            everyone.shuffled().each { restart it }
            check awaitLeader(name) != null, 'a leader is elected'
            check awaitApplied(name, last, 30_000), 'every server has replayed the log'
            everyone.each { server ->
                check written.every { pair, value -> get(server, pair) == value }, "${server} holds all ${pairs} pairs again"
            }
            check written.every { pair, value -> readThrough(everyone.first(), kvGet(pair))?.result?.value == value }, 'a read through the leader sees every pair'
            Map after = proposeToLeader(name, kvPut(key('after'), 'still writing'))
            check after.status == 'COMMITTED' && after.applied == true, 'it takes new writes'
            check awaitApplied(name, after.index as long), 'every server applies them'
            checkCheckpointsAgree(name)
        }
    ]
} finally {
    stopSwarm name
}
