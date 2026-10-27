// Walks a swarm through each state the observatory shows, holding each for a while so it can be watched, and puts
// everything back afterwards: frozen, partitioned, degraded, stopped, recovering after a restart, and left.
// Usage: swarm showcase.groovy [swarm] [holdSeconds]
@groovy.transform.BaseScript SwarmScript swarm

String name = args ? args[0] : 'kv'
long hold = (args && args.size() > 1 ? args[1] as long : 12) * 1000
def ids = alive(name)
if (ids.size() < 5) { throw new IllegalStateException("The showcase needs 5 servers in ${name}, it has ${ids.size()}") }

try {
    log "-- frozen: ${ids[0]} goes silent; its peers soon rate it suspect, then dead"
    frozenFor ids[0], hold
    awaitAlive name, ids.size()

    log "-- partitioned: ${ids[1]} is cut off from the rest"
    isolate ids[1]
    pause hold
    heal()
    awaitAlive name, ids.size()

    log "-- degraded: ${ids[2]} gets 300 ms of delay and 30% loss"
    delay([ids[2]], 300)
    loss([ids[2]], 30)
    hold.intdiv(1000).times { put ids[it % ids.size()], key("showcase-${it}"), it }
    pause hold
    heal()

    log "-- stopped, then recovering: ${ids[3]} is killed, then restarted and catches up"
    kill ids[3]
    put ids[0], key('while-stopped'), 'written while it was down'
    pause hold
    restart ids[3]
    awaitAlive name, ids.size()
    awaitInSync name

    log "-- a new server starts, catches up, then leaves gossip and is removed"
    def added = add(name, 1)
    awaitAlive name, ids.size() + 1
    pause hold.intdiv(2)
    leave added[0]
    pause hold
    remove added[0]
} finally {
    heal()
    servers(name).findAll { it.frozen }.each { thaw it.serverId }
    servers(name).findAll { !it.alive && it.serverId in ids }.each { restart it.serverId }
}
awaitAlive name, ids.size()
awaitInSync name
