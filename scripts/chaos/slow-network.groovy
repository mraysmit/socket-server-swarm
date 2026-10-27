// Chaos: a slow network everywhere, and one server losing a third of its traffic, while clients keep writing.
// Steady state: once the network is healed the swarm converges and holds every acknowledged write; while it is bad,
// writes are still acknowledged and the swarm still converges, only more slowly.
// Usage: swarm chaos/slow-network.groovy [swarm] [delayMillis] [lossPercent]
@groovy.transform.BaseScript SwarmScript swarm

String name = args ? args[0] : 'kv'
long millis = args && args.size() > 1 ? args[1] as long : 150
int percent = args && args.size() > 2 ? args[2] as int : 33
def everyone = alive(name)
String lossy = randomServer(name)
def written = [:]

experiment "${millis} ms of delay everywhere and ${percent}% loss at ${lossy}", [
    steady: { awaitInSync(name, 30_000) && written.every { key, value -> alive(name).every { get(it, key) == value } } },
    method: {
        delay everyone, millis
        loss([lossy], percent)
        20.times { i ->
            String on = everyone[i % everyone.size()]
            put on, key("slow-${i}"), "written on ${on}"
            written[key("slow-${i}")] = "written on ${on}"
        }
        check awaitInSync(name, 60_000), 'the swarm converges even while the network is bad'
        heal()
    },
    rollback: { heal() }
]
