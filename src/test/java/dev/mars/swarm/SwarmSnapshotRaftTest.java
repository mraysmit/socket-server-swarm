package dev.mars.swarm;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** What the page says of a Raft swarm as a whole, decided from what each server reports of itself. */
class SwarmSnapshotRaftTest {
    private static Map<String, Object> server(String id, String role, long term, long commit, long checkpoint, String hash) {
        var raft = new LinkedHashMap<String, Object>();
        raft.put("state", "running");
        raft.put("role", role);
        raft.put("term", term);
        raft.put("commitIndex", commit);
        raft.put("appliedIndex", commit);
        raft.put("checkpointIndex", checkpoint);
        raft.put("checkpointHash", hash);
        var server = new LinkedHashMap<String, Object>();
        server.put("serverId", id);
        server.put("alive", true);
        server.put("raft", raft);
        return server;
    }

    private static Map<String, Object> follower(String id, long term) { return server(id, "FOLLOWER", term, 10, 8, "same"); }

    private static Map<String, Object> leader(String id, long term) { return server(id, "LEADER", term, 10, 8, "same"); }

    private static Map<String, Object> down(String id) {
        var server = new LinkedHashMap<String, Object>();
        server.put("serverId", id);
        server.put("alive", false);
        server.put("raft", null);
        return server;
    }

    @Test void aHealthySwarmShowsItsLeaderItsTermAndEveryServerAgreeing() {
        var summary = SwarmSnapshot.raftSummary(List.of(follower("a", 7), leader("b", 7), follower("c", 7)));
        assertEquals("b", summary.get("leader"));
        assertEquals(7L, summary.get("term"));
        assertEquals(10L, summary.get("commitIndex"));
        assertEquals(3, summary.get("agree"));
        assertEquals(3, summary.get("servers"));
        assertEquals(List.of(), summary.get("staleLeaders"));
    }

    @Test void aLeaderWithANewerOneElsewhereIsStale() {
        var summary = SwarmSnapshot.raftSummary(List.of(leader("old", 3), follower("b", 3), leader("new", 4), follower("d", 4), follower("e", 4)));
        assertEquals("new", summary.get("leader"));
        assertEquals(4L, summary.get("term"));
        assertEquals(List.of("old"), summary.get("staleLeaders"));
    }

    @Test void aMajorityPastALeadersTermMakesItStaleBeforeAnotherIsElected() {
        var summary = SwarmSnapshot.raftSummary(List.of(leader("old", 3), follower("b", 3), server("c", "CANDIDATE", 4, 10, 8, "same"),
                server("d", "CANDIDATE", 4, 10, 8, "same"), follower("e", 4)));
        assertNull(summary.get("leader"), "It can commit nothing more, so nothing leads");
        assertEquals(4L, summary.get("term"));
        assertEquals(List.of("old"), summary.get("staleLeaders"));
    }

    @Test void aLoneServerRunningUpTheTermDoesNotMakeAWorkingLeaderStale() {
        var summary = SwarmSnapshot.raftSummary(List.of(leader("a", 5), follower("b", 5), follower("c", 5), follower("d", 5), server("cut-off", "CANDIDATE", 12, 10, 8, "same")));
        assertEquals("a", summary.get("leader"), "A majority is still with it, so it still commits");
        assertEquals(5L, summary.get("term"), "The line says the term of the one that leads");
        assertEquals(List.of(), summary.get("staleLeaders"));
    }

    @Test void twoLeadersInOneTermAreCalledOutBecauseRaftMustNeverAllowIt() {
        var summary = SwarmSnapshot.raftSummary(List.of(leader("a", 5), leader("b", 5), follower("c", 5)));
        assertEquals(5L, summary.get("twoLeadersInTerm"));
        assertFalse(SwarmSnapshot.raftSummary(List.of(leader("a", 5), follower("b", 5))).containsKey("twoLeadersInTerm"));
    }

    @Test void aServerReportingAnotherCheckpointForOnePositionDoesNotAgree() {
        var summary = SwarmSnapshot.raftSummary(List.of(leader("a", 5), follower("b", 5), server("odd", "FOLLOWER", 5, 10, 8, "other")));
        assertEquals(2, summary.get("agree"));
        assertEquals(List.of("odd"), summary.get("differ"));
    }

    @Test void aServerBehindHasNotDisagreedItSimplyHasNotGotThereYet() {
        var summary = SwarmSnapshot.raftSummary(List.of(leader("a", 5), follower("b", 5), server("late", "FOLLOWER", 5, 3, 0, "")));
        assertEquals(3, summary.get("agree"));
        assertEquals(List.of(), summary.get("differ"));
    }

    @Test void aServerDownOrFailedCountsAmongTheServersButNotAmongThoseAgreeing() {
        var failed = leader("failed", 9);
        @SuppressWarnings("unchecked") var raft = (Map<String, Object>) failed.get("raft");
        raft.put("state", "failed");
        var summary = SwarmSnapshot.raftSummary(List.of(leader("a", 5), follower("b", 5), down("gone"), failed));
        assertEquals(2, summary.get("agree"));
        assertEquals(4, summary.get("servers"));
        assertEquals("a", summary.get("leader"), "A server that has failed leads nothing, whatever it last said");
        assertEquals(List.of("failed"), summary.get("failed"));
    }

    @Test void aServerThatCannotApplyIsNamedAsWaiting() {
        var waiting = follower("no-kv", 5);
        @SuppressWarnings("unchecked") var raft = (Map<String, Object>) waiting.get("raft");
        raft.put("waitingFor", Map.of("index", 2, "instruction", "kv", "reason", "not loaded"));
        var summary = SwarmSnapshot.raftSummary(List.of(leader("a", 5), waiting));
        assertEquals(List.of("no-kv"), summary.get("waiting"));
    }

    @Test void aSwarmWithNoServerAnsweringHasNoLeaderAndAgreesOnNothing() {
        var summary = SwarmSnapshot.raftSummary(List.of(down("a"), down("b")));
        assertNull(summary.get("leader"));
        assertEquals(0L, summary.get("term"));
        assertEquals(0, summary.get("agree"));
        assertEquals(2, summary.get("servers"));
    }

    @Test void numbersReadFromAServersReplyAreNotAssumedToBeLongs() {
        var odd = leader("a", 5);
        @SuppressWarnings("unchecked") var raft = (Map<String, Object>) odd.get("raft");
        raft.put("term", java.math.BigInteger.valueOf(5));
        raft.put("commitIndex", 10);
        var summary = SwarmSnapshot.raftSummary(List.of(odd));
        assertEquals(5L, summary.get("term"));
        assertEquals(10L, summary.get("commitIndex"));
    }
}
