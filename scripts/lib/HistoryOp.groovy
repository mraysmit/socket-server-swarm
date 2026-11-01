import groovy.transform.CompileStatic

/** One client operation, for {@link HistoryChecker}. {@code value} null means the key holds nothing. {@code end} null is a write whose outcome is unknown. */
@CompileStatic
final class HistoryOp {
    final String key
    final boolean write
    final String value
    final long start
    final Long end

    HistoryOp(String key, boolean write, String value, long start, Long end) {
        this.key = key
        this.write = write
        this.value = value
        this.start = start
        this.end = end
    }

    boolean isDefinite() { end != null }

    @Override String toString() {
        "${write ? 'write' : 'read'} ${key}=${value} [${start}, ${end == null ? 'unknown' : end}]"
    }
}
