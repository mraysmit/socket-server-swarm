// Distributed kv: the unchanged kv plugin, on a Raft cluster. A write proposed through any server is committed, applied on every server, and
// can be read back through any of them, each read going to the leader and confirmed by a majority first.
// It leaves its swarm running to look at, unless given a third argument. It needs the Raft and kv plugins in the harness's --plugins-dir.
// Usage: swarm raft-kv.groovy [swarm] [servers] [stop]
@groovy.transform.BaseScript SwarmScript swarm

String name = args ? args[0] : 'distributed'
int size = args && args.size() > 1 ? args[1] as int : 5
boolean stopAtEnd = args && args.size() > 2

if (snapshot().swarms.any { it.name == name }) { stopSwarm name }
def everyone = startRaft(name, size)
try {
    check awaitLeader(name) != null, "${size} servers elect a leader"
    String colour = key('colour')

    String asked = everyone.last()
    Map answer = proposeToLeader(name, kvPut(colour, 'blue'))
    check answer.status == 'COMMITTED' && answer.applied == true, "a kv put commits, and is applied (${answer.status})"
    log "Committed at position ${answer.index}, with kv's answer ${answer.result}"

    check awaitApplied(name, answer.index as long), 'every server has applied it'
    everyone.each { check get(it, colour) == 'blue', "${it} holds it in its own kv" }

    everyone.each { server ->
        Map read = readThrough(server, kvGet(colour))
        check read?.result?.value == 'blue', "a read through ${server} sees it"
    }

    Map typo = propose(asked, [instruction: 'kvv', parameters: [operation: 'put', key: colour, value: 'red']])
    check typo.status == 'REFUSED', "a typo is refused and never reaches the log (${typo.reason})"
    Map sneaked = raftRead(leader(name), kvPut(colour, 'red'))
    check sneaked.status == 'REFUSED', "a write cannot be run as a read (${sneaked.reason})"

    long last = answer.index as long
    8.times { last = proposeToLeader(name, kvPut(key("more-${it}"), it)).index as long }
    check awaitApplied(name, last), 'more writes reach every server'
    checkCheckpointsAgree(name)
} finally {
    if (stopAtEnd) { stopSwarm name }
}
