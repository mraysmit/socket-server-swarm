import groovy.transform.CompileStatic

/**
 * Decides whether what clients saw could have come from one copy of the data, each operation taking effect at some moment between its
 * call and its answer, which is what linearizable means. Each key is a register, checked by itself, by searching for an order that explains
 * its operations. It is the search of Wing and Gong, with what has been tried remembered, as in Lowe's refinement of it.
 *
 * <p>A write whose outcome is unknown may have taken effect at any time after it was made, however long after, or never, and the search allows
 * both. Each write must give its key a different value, or a read could not say which write it saw.
 */
@CompileStatic
final class HistoryChecker {
    /** {@code violations} names each key no order explains, with its operations. {@code undecided} names each the search gave up on, which proves nothing. */
    static final class Result {
        final int keys
        final int operations
        final Map<String, List<HistoryOp>> violations
        final List<String> undecided

        Result(int keys, int operations, Map<String, List<HistoryOp>> violations, List<String> undecided) {
            this.keys = keys
            this.operations = operations
            this.violations = violations
            this.undecided = undecided
        }

        boolean isLinearizable() { violations.isEmpty() && undecided.isEmpty() }
    }

    /** More than any history a script makes should need: a search this long has met something it cannot decide. */
    private static final int MAX_STATES = 5_000_000

    private HistoryChecker() { }

    static Result check(Collection<HistoryOp> history) {
        def byKey = new TreeMap<String, List<HistoryOp>>()
        for (op in history) { byKey.computeIfAbsent(op.key) { new ArrayList<HistoryOp>() }.add(op) }
        def violations = new LinkedHashMap<String, List<HistoryOp>>()
        def undecided = new ArrayList<String>()
        byKey.each { String key, List<HistoryOp> ops ->
            Boolean explained = new Search(ops).run()
            if (explained == null) { undecided.add(key) }
            else if (!explained) { violations[key] = ops }
        }
        new Result(byKey.size(), history.size(), violations, undecided)
    }

    private static final class Search {
        private final List<HistoryOp> ops
        private final Set<List<Object>> tried = new HashSet<List<Object>>()
        private final BitSet done = new BitSet()
        private final int definite
        private int doneDefinite
        private boolean gaveUp

        Search(List<HistoryOp> given) {
            // An unknown write nobody saw can be left out: with values all different, no read depends on it, and dropping it changes no other read.
            Set<String> read = new HashSet<String>()
            for (op in given) { if (!op.write) { read.add(op.value) } }
            ops = new ArrayList<HistoryOp>()
            for (op in given) { if (op.definite || read.contains(op.value)) { ops.add(op) } }
            ops.sort { HistoryOp a, HistoryOp b -> a.start <=> b.start }
            int count = 0
            for (op in ops) { if (op.definite) { count++ } }
            definite = count
        }

        Boolean run() {
            boolean found = extend(null)
            gaveUp && !found ? null : found
        }

        private boolean extend(String state) {
            if (doneDefinite == definite) { return true }
            // Nothing can take effect after an operation that has not yet taken effect has already returned.
            long earliestEnd = Long.MAX_VALUE
            for (int i = done.nextClearBit(0); i < ops.size(); i = done.nextClearBit(i + 1)) {
                HistoryOp op = ops.get(i)
                if (op.definite && op.end < earliestEnd) { earliestEnd = op.end }
            }
            for (int i = done.nextClearBit(0); i < ops.size(); i = done.nextClearBit(i + 1)) {
                HistoryOp op = ops.get(i)
                if (op.start > earliestEnd) { break }
                if (!op.write && op.value != state) { continue }
                done.set(i)
                if (op.definite) { doneDefinite++ }
                String next = op.write ? op.value : state
                List<Object> seen = [done.clone(), next] as List<Object>
                if (tried.add(seen)) {
                    if (tried.size() > MAX_STATES) { gaveUp = true }
                    else if (extend(next)) { return true }
                }
                done.clear(i)
                if (op.definite) { doneDefinite-- }
                if (gaveUp) { return false }
            }
            false
        }
    }
}
