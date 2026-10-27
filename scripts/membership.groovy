// Grows a swarm by two servers, then shrinks it again: one departs gracefully, one disappears without warning.
// Gossip must count each change, and the new servers must catch up with the pairs already there.
// Usage: swarm membership.groovy [swarm]
@groovy.transform.BaseScript SwarmScript swarm

String name = args ? args[0] : 'kv'
int before = alive(name).size()
put alive(name).first(), key('membership'), 'before the new servers'

def added = add(name, 2)
awaitAlive name, before + 2
awaitInSync name
added.each { check get(it, key('membership')) == 'before the new servers', "${it} caught up when it joined" }

remove added[0]           // tells its peers it is going, then stops
kill added[1]             // stops without a word; its peers find out by timeout
awaitAlive name, before
remove added[1]
check alive(name).size() == before, "${name} is back to ${before} servers"
