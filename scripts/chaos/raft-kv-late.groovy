// Chaos: activate kv late. A Raft swarm starts without kv and commits kv commands, which wait to be applied. Its servers then restart one at
// a time with kv, as an operator would add a plugin, because plugins load only at start. Each must catch up, with nothing skipped, and they must
// all agree, while the cluster stays available throughout.
// It starts its own swarm, and stops it at the end. It needs the Raft and kv plugins in the harness's --plugins-dir.
// Usage: swarm chaos/raft-kv-late.groovy [swarm] [servers] [pairs]
@groovy.transform.BaseScript SwarmScript swarm

String name = args ? args[0] : 'raftkvlate'
int size = args && args.size() > 1 ? args[1] as int : 3
int pairs = args && args.size() > 2 ? args[2] as int : 10

def everyone = startRaft(name, size, 'kv')
try {
    check awaitLeader(name) != null, "${size} servers elect a leader, with no kv anywhere"
    check !servers(name).any { it.kv }, 'no server has kv'
    def written = [:]
    long last = 0
    pairs.times {
        String pair = key("pair-${it}")
        written[pair] = "value-${it}".toString()
        Map answer = proposeToLeader(name, kvPut(pair, written[pair]))
        check answer.status == 'COMMITTED' && answer.applied == false, "${pair} commits, and waits to be applied (${answer.status}, applied ${answer.applied})"
        last = answer.index as long
    }
    check awaitCommitted(name, last), 'every server has committed them all'
    check waitFor('every server says what it waits for', 15_000) { servers(name).every { it.raft?.waitingFor?.instruction == 'kv' } }, 'every server says it is waiting for kv'
    check servers(name).every { (it.raft?.appliedIndex as long) < last }, 'none has applied them'
    Map refused = raftRead(leader(name), kvGet(written.keySet().first()))
    check refused.status != 'READ', "nothing can be read yet, because it could not include them (${refused.status})"

    everyone.each { server ->
        restartWithAllPlugins server
        check awaitLeader(name) != null, "there is a leader while ${server} comes back"
        check awaitApplied(name, last, 15_000, [server]), "${server} has caught up"
        check written.every { pair, value -> get(server, pair) == value }, "${server} holds all ${pairs} pairs"
        Map during = proposeToLeader(name, kvPut(key("during-${server}"), 'taken'))
        check during.status == 'COMMITTED', "the cluster still commits (${during.status})"
        written[key("during-${server}")] = 'taken'
        last = during.index as long
    }
    check !servers(name).any { it.raft?.waitingFor }, 'nobody is waiting any more'
    check awaitApplied(name, last), 'every server has applied everything'
    everyone.each { server ->
        check written.every { pair, value -> get(server, pair) == value }, "${server} holds everything, including what came during the restarts"
    }
    check readThrough(everyone.first(), kvGet(written.keySet().first()))?.result?.value == written.values().first(), 'it can be read through Raft now'
    checkCheckpointsAgree(name)
} finally {
    stopSwarm name
}
