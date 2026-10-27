// Chaos: freeze a server, as a long GC pause or a debugger would, while the others keep taking writes.
// First a pause long enough for its peers to suspect it, then one long enough for them to declare it dead.
// Steady state: once thawed it is a member again, and every server holds every acknowledged write.
// The pauses suit the harness's --gossip-fast timings (suspect after 4 s, dead after 10 s).
// Usage: swarm chaos/freeze.groovy [swarm]
@groovy.transform.BaseScript SwarmScript swarm

String name = args ? args[0] : 'kv'
int size = alive(name).size()
def written = [:]

def steady = { awaitAlive(name, size) && awaitInSync(name) && written.every { key, value -> alive(name).every { get(it, key) == value } } }

[[seconds: 6, peersSee: 'SUSPECT'], [seconds: 15, peersSee: 'DEAD']].each { pause ->
    String victim = randomServer(name)
    experiment "${victim} frozen for ${pause.seconds} s", [
        steady: steady,
        method: {
            frozenFor(victim, pause.seconds * 1000) {
                String writer = alive(name).find { it != victim }
                String key = "frozen-${victim}-${pause.seconds}"
                put writer, key, "written while ${victim} was frozen"
                written[key] = "written while ${victim} was frozen"
                // The others must notice the silence: the frozen server is no longer rated ALIVE by any of them.
                check waitFor("peers stop rating ${victim} ALIVE", pause.seconds * 1000 - 500) {
                    servers(name).findAll { it.alive }.every { it.gossip?.status?.assessments?.ALIVE == size - 1 }
                }, "the others noticed ${victim} fell silent"
                // And they reach the expected verdict before the thaw: SUSPECT for the short pause, DEAD for the long one.
                check waitFor("peers rate ${victim} ${pause.peersSee}", pause.seconds * 1000 - 500) {
                    servers(name).findAll { it.alive }.every { it.gossip?.status?.assessments?.get(pause.peersSee) == 1 }
                }, "the others rate ${victim} ${pause.peersSee}"
            }
        },
        rollback: { thaw victim }
    ]
}
