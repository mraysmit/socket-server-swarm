// Chaos: for several rounds, kill a random server while clients keep writing, then restart it.
// Steady state: every server that is up holds the same pairs, and no acknowledged write is lost.
// Usage: swarm chaos/random-kills.groovy [swarm] [rounds]
@groovy.transform.BaseScript SwarmScript swarm

String name = args ? args[0] : 'kv'
int rounds = args && args.size() > 1 ? args[1] as int : 5
int size = alive(name).size()
def written = [:]   // every write a server acknowledged: key -> value

def writeSome = { int count ->
    count.times {
        String key = this.key("chaos-${written.size()}")
        String on = randomServer(name)
        try { put on, key, "${on}@${System.nanoTime()}"; written[key] = get(on, key) }
        catch (Exception refused) { log "write to ${on} refused: ${refused.message}" }
    }
}

experiment "a swarm of ${size} survives ${rounds} random kills", [
    steady: {
        awaitInSync(name) && written.every { key, value -> alive(name).every { get(it, key) == value } }
    },
    method: {
        rounds.times { round ->
            String victim = randomServer(name)
            log "Round ${round + 1} of ${rounds}"
            kill victim
            writeSome 10
            pause 1_000
            restart victim
            awaitAlive name, size
            writeSome 5
        }
    },
    rollback: {
        // Anything still down from a failed round is started again.
        servers(name).findAll { !it.alive }.each { restart it.serverId }
    }
]
check written.size() > 0, "${written.size()} writes were acknowledged during the experiment"
