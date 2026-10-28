// Chaos: start a swarm of ten from nothing, then break it four ways while clients keep writing.
//   1. cold start: ten servers find one another and agree on one store
//   2. a third of the swarm crashes at once, and the survivors carry on without it
//   3. the swarm splits four against six, both sides take writes, then it heals
//   4. gray failures together: two servers slowed and one losing traffic under a load test, then one frozen past the
//      dead timeout as well
// Steady state: every server rates all ten ALIVE, every store holds the same pairs, and no acknowledged write is lost.
// The swarm is left running afterwards, for the page; stop it there or with `stop <swarm>` in the console.
// The waits suit the harness's --gossip-fast timings (suspect after 4 s, dead after 10 s).
// Usage: swarm chaos/swarm-of-ten.groovy [swarm] [servers]
@groovy.transform.BaseScript SwarmScript swarm

String name = args ? args[0] : 'ten'
int size = args && args.size() > 1 ? args[1] as int : 10
if (size < 6) { throw new IllegalArgumentException("The experiments need at least 6 servers, not ${size}") }
if (snapshot().swarms.any { it.name == name }) {
    throw new IllegalStateException("A swarm named ${name} is already running; stop it or name another")
}
def written = [:]   // every write a server acknowledged: key -> value

/** Writes {@code count} pairs round-robin across {@code on}, keeping each one a server acknowledged. */
def writeTo = { List<String> on, String label, int count ->
    count.times { i ->
        String server = on[i % on.size()]
        String key = this.key("${label}-${i}")
        try { put server, key, "${label} ${i} on ${server}"; written[key] = "${label} ${i} on ${server}" }
        catch (Exception refused) { log "write to ${server} refused: ${refused.message}" }
    }
}

def steady = {
    awaitAlive(name, size) && awaitInSync(name, 30_000) &&
            written.every { key, value -> alive(name).every { get(it, key) == value } }
}

// ---- 1. Cold start ------------------------------------------------------------------------------------------------
log "=== Starting a swarm of ${size}: ${name}"
def everyone = start(name, size)
check awaitAlive(name, size, 60_000), "${size} servers started from nothing find one another"
writeTo everyone, 'start', size
check steady(), "the new swarm agrees on one store holding every write"

// ---- 2. A third crashes at once -------------------------------------------------------------------------------------
def crashed = everyone.shuffled().take(size.intdiv(3))
def survivors = everyone - crashed
experiment "${crashed.size()} of ${size} servers crash at once", [
    steady: steady,
    method: {
        crashed.each { kill it }
        // Written only on survivors: a write held only by a server that is down cannot reach the others yet.
        writeTo survivors, 'crash', 15
        check awaitAlive(name, survivors.size()), "the ${survivors.size()} survivors stop counting the crashed servers"
        check awaitInSync(name), 'the survivors still agree on one store'
        crashed.each { restart it }
    },
    rollback: { servers(name).findAll { !it.alive && !it.frozen }.each { restart it.serverId } }
]

// ---- 3. Split four against six ------------------------------------------------------------------------------------
def minority = everyone.shuffled().take(size.intdiv(2) - 1)
def majority = everyone - minority
def sees = { List<String> side ->
    servers(name).findAll { it.serverId in side }.every { it.gossip?.status?.assessments?.ALIVE == side.size() }
}
experiment "a split into ${minority.size()} and ${majority.size()} servers heals", [
    steady: steady,
    method: {
        partition minority, majority
        check waitFor('each side sees only itself', 30_000) { sees(minority) && sees(majority) },
                "each side rates only its own ${minority.size()} or ${majority.size()} servers ALIVE"
        writeTo minority, 'minority', 6
        writeTo majority, 'majority', 6
        // The same key on both sides: once they meet again the later write must win everywhere.
        String both = key('split-both')
        put minority[0], both, 'the earlier write, on the minority'
        pause 50
        put majority[0], both, 'the later write, on the majority'
        written[both] = 'the later write, on the majority'
        pause 3_000   // several push and sync rounds, which must not cross the split
        check get(majority[0], key('minority-0')) == null, 'the majority does not see the minority writes while split'
        check get(minority[0], key('majority-0')) == null, 'the minority does not see the majority writes while split'
        heal()
    },
    rollback: { heal() }
]

// ---- 4. Gray failures together ------------------------------------------------------------------------------------
def shuffled = everyone.shuffled()
String frozen = shuffled[0]
def slow = shuffled[1..2]
String lossy = shuffled[3]
def healthy = shuffled.drop(4)
experiment "${frozen} frozen, ${slow} slowed by 200 ms, ${lossy} losing 30% of its traffic", [
    steady: steady,
    method: {
        // Its peers keep records of earlier boots too, so the check is about the boot that is frozen now.
        String boot = bootId(frozen)
        delay slow, 200
        loss([lossy], 30)
        // The harness's own traffic is never faulted, so every server still answers it; the frozen one would not.
        def result = load(name, 2_000, 50)
        check result.failed == 0, "a load test of ${result.requests} requests passes on the degraded network"
        frozenFor(frozen, 13_000) {
            writeTo healthy, 'gray', 12
            check waitFor("peers rate ${frozen} DEAD", 12_500) {
                alive(name).every { rating(it, frozen, boot) == 'DEAD' }
            }, "the others rate ${frozen} DEAD while it is frozen"
        }
        heal()
    },
    rollback: {
        heal()
        servers(name).findAll { it.frozen }.each { thaw it.serverId }
    }
]

log "=== ${name} is still running with ${alive(name).size()} servers; ${written.size()} acknowledged writes checked"
