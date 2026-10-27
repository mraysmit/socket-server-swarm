// Chaos: restart every server one after another, as a deployment would, with writes before each restart.
// Steady state: the swarm converges, and every server holds every acknowledged write.
// Usage: swarm chaos/rolling-restart.groovy [swarm]
@groovy.transform.BaseScript SwarmScript swarm

String name = args ? args[0] : 'kv'
int size = alive(name).size()
def written = [:]

experiment "a rolling restart of ${size} servers loses nothing", [
    steady: { awaitInSync(name) && written.every { key, value -> alive(name).every { get(it, key) == value } } },
    method: {
        alive(name).each { server ->
            String other = alive(name).find { it != server }
            String key = "rolling-${server}"
            put other, key, "written before ${server} restarted"
            written[key] = "written before ${server} restarted"
            kill server
            restart server
            awaitAlive name, size
            awaitInSync name
        }
    },
    rollback: { servers(name).findAll { !it.alive }.each { restart it.serverId } }
]
