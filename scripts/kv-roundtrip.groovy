// Writes a pair on one server, reads it back from every other, then deletes it everywhere.
// Usage: swarm kv-roundtrip.groovy [swarm]
@groovy.transform.BaseScript SwarmScript swarm

String name = args ? args[0] : 'kv'
def servers = alive(name)
def writer = servers.first()
String key = this.key("roundtrip")

put writer, key, [colour: 'blue', size: 3]
awaitInSync name
servers.each { check get(it, key) == [colour: 'blue', size: 3], "${it} reads the value written on ${writer}" }

delete servers.last(), key
awaitInSync name
servers.each { check get(it, key) == null, "${it} no longer holds ${key}" }
