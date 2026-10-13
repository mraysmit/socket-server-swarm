package dev.mars.swarm;

import java.util.List;

public record LoadResult(String swarm, int requests, int passed, int failed,
                         long elapsedMillis, List<String> failures) {
    public LoadResult { failures = List.copyOf(failures); }
    public boolean successful() { return failed == 0 && passed == requests; }
    @Override public String toString() {
        return "Swarm test " + (successful() ? "PASSED" : "FAILED") + " | swarm=" + swarm
                + " | requests=" + requests + " | passed=" + passed + " | failed=" + failed
                + " | elapsedMs=" + elapsedMillis;
    }
}
