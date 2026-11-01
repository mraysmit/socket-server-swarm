// Proof that the history checker can find what it is for, since a checker that never fails proves nothing.
// First, histories written by hand, valid and invalid, whose answers are known. Then the same kind of split that raft-kv-partition survives, on a
// swarm whose kv replicates by itself, newest write winning: that was never linearizable, so the checker must say so. If it can't, it isn't working.
// It starts its own swarm, and stops it at the end. The second part needs the harness started with --kv-replicate.
// Usage: swarm chaos/history-checker.groovy [swarm] [servers] [clients]
@groovy.transform.BaseScript SwarmScript swarm

String name = args ? args[0] : 'lwwsplit'
int size = args && args.size() > 1 ? args[1] as int : 4
int clients = args && args.size() > 2 ? args[2] as int : 4

def w = { String value, long start, Long end -> new HistoryOp('k', true, value, start, end) }
def r = { String value, long start, long end -> new HistoryOp('k', false, value, start, end) }
def explained = { List<HistoryOp> ops -> HistoryChecker.check(ops).linearizable }

check explained([]), 'an empty history is fine'
check explained([r(null, 0, 1)]), 'reading nothing from a key never written is fine'
check explained([w('a', 0, 1), r('a', 2, 3)]), 'reading what was written is fine'
check !explained([w('a', 0, 1), r('b', 2, 3)]), 'reading what nobody wrote is not'
check !explained([w('a', 0, 1), r(null, 2, 3)]), 'an acknowledged write that is gone is a lost write'
check !explained([w('a', 0, 1), w('b', 2, 3), r('a', 4, 5)]), 'reading a value already overwritten is a stale read'
check explained([w('a', 0, 10), w('b', 1, 11), r('a', 12, 13)]), 'of two writes at once, either may land last'
check explained([w('a', 0, 10), w('b', 1, 11), r('b', 12, 13)]), 'and so may the other'
check !explained([w('a', 0, 10), w('b', 1, 11), r('a', 12, 13), r('b', 14, 15)]), 'but a value that has been replaced cannot come back'
check !explained([w('a', 0, 1), w('b', 2, 3), r('b', 4, 5), r('a', 6, 7)]), 'reads may not go backward'
check explained([w('a', 0, 1), w('b', 2, 20), r('a', 3, 4), r('b', 5, 6), r('b', 7, 8)]), 'a write in progress may be seen as soon as it takes effect'
check !explained([w('a', 0, 1), w('b', 2, 20), r('b', 3, 4), r('a', 5, 6)]), 'and once seen, it has taken effect'

check explained([w('a', 0, 1), w('x', 2, null), r('a', 3, 4)]), 'an unknown write may never have happened'
check explained([w('a', 0, 1), w('x', 2, null), r('x', 3, 4)]), 'or may have happened'
check explained([w('a', 0, 1), w('x', 2, null), r('a', 100, 101), r('x', 200, 201)]), 'however long after it was made'
check !explained([w('x', 5, null), r('x', 0, 1)]), 'but not before it was made'
check !explained([w('a', 0, 1), w('x', 2, null), r('x', 3, 4), r('a', 5, 6)]), 'and it cannot be undone once seen'
check explained([w('x', 0, null), w('y', 1, null), w('z', 2, null), r(null, 3, 4)]), 'several unknown writes may all have come to nothing'

check HistoryChecker.check([new HistoryOp('a', true, '1', 0, 1), new HistoryOp('b', false, '1', 2, 3)]).violations.keySet() == ['b'] as Set,
        'each key stands alone, and the one that fails is named'

// A lot of writes in progress at once must still come to a decision.
def wide = (0..<60).collect { w("v${it}".toString(), 0, 1000) } + [r('v59', 1001, 1002)]
check explained(wide), 'sixty writes at once are decided'
def impossible = (0..<16).collect { w("v${it}".toString(), it * 10L, it * 10L + 1) } + [r('v0', 1000, 1001)]
check !explained(impossible), 'and a long history that cannot be explained is found out'

def everyone = start(name, size)
try {
    check kvStatus(everyone.first()).replication == 'on', 'kv replicates by itself here, as it needs --kv-replicate for'
    check awaitInSync(name), "${size} servers are in sync"
    def writes = { String chosen, String value ->
        put(everyone[new Random().nextInt(everyone.size())], chosen, value)
        'ok'
    }
    def reads = { String chosen ->
        def found = kv(everyone[new Random().nextInt(everyone.size())], [operation: 'get', key: chosen])
        found.found ? [found.value] : [null]
    }
    def history = clientsDuring('lww', clients, 3, writes, reads) {
        pause 1_500
        partition everyone.take(size.intdiv(2)), everyone.drop(size.intdiv(2))
        pause 4_000
        heal()
        pause 1_500
    }
    def result = HistoryChecker.check(history)
    log "History: ${result.operations} operations on ${result.keys} keys. Keys no order explains: ${result.violations.keySet()}"
    check !result.violations.isEmpty(), 'it finds violations in what newest-write-wins replication did through a split'
} finally {
    heal()
    stopSwarm name
}
