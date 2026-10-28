// Chaos: servers join and go continuously while clients write; every other one goes without warning.
// Steady state: the swarm converges, and every server holds every acknowledged write.
// Usage: swarm chaos/churn.groovy [swarm] [cycles]
@groovy.transform.BaseScript SwarmScript swarm

String name = args ? args[0] : 'kv'
int cycles = args && args.size() > 1 ? args[1] as int : 4
int size = alive(name).size()
def written = [:]
def joined = []

experiment "${cycles} cycles of servers joining and going", [
    steady: { awaitInSync(name) && written.every { key, value -> alive(name).every { get(it, key) == value } } },
    method: {
        cycles.times { cycle ->
            joined += add(name, 1)
            awaitAlive name, size + 1
            String going = joined.first()
            // Written on a server that stays: a write held only by a server killed before it replicates is lost, which
            // is how asynchronous replication behaves, not what this experiment is about.
            String writer = (alive(name) - going).shuffled().first()
            String key = this.key("churn-${cycle}")
            put writer, key, "cycle ${cycle}"
            written[key] = "cycle ${cycle}"
            if (cycle % 2 == 1) { kill going }   // gone without warning: peers find out by timeout
            remove going                          // otherwise a graceful departure
            joined.remove(going)
            awaitAlive name, size
        }
    },
    // A server this experiment added and did not take away again is removed.
    rollback: { joined.each { remove it } }
]
