// Chaos: cut the swarm in two, write on both sides, then heal.
// While split, each side must see only itself and keep its writes to itself; a key written on both sides is a conflict.
// Steady state after healing: the swarm converges, every acknowledged write is everywhere, and the conflicting key
// holds the later write on every server (last writer wins).
// Usage: swarm chaos/partition.groovy [swarm]
@groovy.transform.BaseScript SwarmScript swarm

String name = args ? args[0] : 'kv'
def everyone = alive(name)
int size = everyone.size()
def minority = everyone.take(size.intdiv(2))
def majority = everyone.drop(size.intdiv(2))
def written = [:]

def sees = { List<String> side -> servers(name).findAll { it.serverId in side }.every { it.gossip?.status?.assessments?.ALIVE == side.size() } }

experiment "a split into ${minority.size()} and ${majority.size()} servers heals", [
    steady: { awaitAlive(name, size) && awaitInSync(name) && written.every { key, value -> alive(name).every { get(it, key) == value } } },
    method: {
        partition minority, majority
        check waitFor('each side sees only itself', 30_000) { sees(minority) && sees(majority) },
                "${minority} and ${majority} each rate only their own side ALIVE"

        put minority[0], 'split-minority', 'written on the minority side'
        put majority[0], 'split-majority', 'written on the majority side'
        written['split-minority'] = 'written on the minority side'
        written['split-majority'] = 'written on the majority side'
        pause 3_000   // several push and sync rounds
        check get(majority.last(), 'split-minority') == null, 'the majority does not see the minority write while split'
        check get(minority.last(), 'split-majority') == null, 'the minority does not see the majority write while split'

        // The same key on both sides: the later write must win everywhere once the sides meet again.
        put minority[0], 'split-both', 'the earlier write, on the minority'
        pause 50
        put majority[0], 'split-both', 'the later write, on the majority'
        written['split-both'] = 'the later write, on the majority'

        heal()
    },
    rollback: { heal() }
]
