// Kills a server without warning, writes while it is down, restarts it, and checks it catches up.
// Usage: swarm kill-and-recover.groovy [swarm]
@groovy.transform.BaseScript SwarmScript swarm

String name = args ? args[0] : 'kv'
int size = alive(name).size()
String victim = randomServer(name)
kill victim

String writer = alive(name).first()
put writer, key('while-down'), "written on ${writer} while ${victim} was down"
awaitInSync name

restart victim
awaitAlive name, size
awaitInSync name
check get(victim, key('while-down')) == "written on ${writer} while ${victim} was down", "${victim} has the write it missed"
