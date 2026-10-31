package dev.mars.swarm;

import dev.mars.client.PeerTraffic;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** The log of calls the page replays: who called whom and how it ended, told apart from clients and the harness's own polls. */
class CallLogTest {
    private static final Map<Integer, String> PORTS = Map.of(9001, "a-Server-001", 9002, "a-Server-002");
    private final CallLog log = new CallLog(PORTS::get);

    private static PeerTraffic.Call call(String source, int port, String messageId) {
        return new PeerTraffic.Call(source, new InetSocketAddress("127.0.0.1", port), "kv", "sync-root", messageId, 42, 1_000, System.nanoTime());
    }

    @Test void aFinishedCallSaysWhoCalledWhomWhatItAskedAndHowItEnded() {
        var call = call("a-Server-001", 9002, "m1");
        log.started(call);
        log.finished(call, PeerTraffic.Outcome.TIMEOUT, "timed out", 5_000_000);
        var view = log.view(0, 100);
        assertEquals(1, view.calls().size());
        var seen = view.calls().getFirst();
        assertEquals("a-Server-001", seen.source());
        assertEquals("a-Server-002", seen.target());
        assertEquals("kv", seen.instruction());
        assertEquals("sync-root", seen.operation());
        assertEquals("m1", seen.messageId());
        assertEquals("TIMEOUT", seen.outcome());
        assertEquals("timed out", seen.detail());
        assertEquals(1_000, seen.startMillis());
        assertEquals(1_005, seen.endMillis());
        assertEquals(42, seen.requestBytes());
        assertTrue(view.inFlight().isEmpty());
        assertEquals(view.lastSeq(), seen.seq());
    }

    @Test void aCallNotFinishedIsInFlightUntilItIs() {
        var call = call("a-Server-001", 9002, "m1");
        log.started(call);
        var flying = log.view(0, 100);
        assertTrue(flying.calls().isEmpty());
        assertEquals(List.of(new CallLog.InFlight("a-Server-001", "a-Server-002", "kv", "sync-root", "m1", 1_000)), flying.inFlight());
        log.finished(call, PeerTraffic.Outcome.OK, null, 1_000_000);
        assertTrue(log.view(0, 100).inFlight().isEmpty());
    }

    @Test void aServersCallToItselfOrToAServerNoLongerListedIsLeftOut() {
        for (var call : List.of(call("a-Server-001", 9001, "self"), call("a-Server-001", 7777, "gone"))) {
            log.started(call);
            assertTrue(log.view(0, 100).inFlight().isEmpty());
            log.finished(call, PeerTraffic.Outcome.REFUSED, "refused", 1);
        }
        assertTrue(log.view(0, 100).calls().isEmpty());
    }

    @Test void theServerAtAPortIsLookedUpOnlyWhenTheLogIsRead() {
        var lookups = new AtomicInteger();
        var counted = new CallLog(port -> { lookups.incrementAndGet(); return PORTS.get(port); });
        var call = call("a-Server-001", 9002, "m1");
        counted.started(call);
        counted.finished(call, PeerTraffic.Outcome.OK, null, 1);
        assertEquals(0, lookups.get(), "An observer runs on the request's thread and must not wait on the manager");
        assertEquals("a-Server-002", counted.view(0, 100).calls().getFirst().target());
    }

    @Test void aRequestAServerLogsIsAClientsUnlessAServerOrTheHarnessSentIt() {
        var peer = call("a-Server-001", 9002, "peer");
        log.started(peer);
        log.serverRequest("a-Server-002", "peer", "kv", 2_000);
        log.serverFinished("a-Server-002", "peer", "OK", null, 2_001);
        log.finished(peer, PeerTraffic.Outcome.OK, null, 1_000_000);

        var poll = UUID.randomUUID();
        log.harnessRequest(poll);
        log.serverRequest("a-Server-002", poll.toString(), "gossip", 2_000);
        log.serverFinished("a-Server-002", poll.toString(), "OK", null, 2_001);

        log.serverRequest("a-Server-002", "script", "kv", 3_000);
        var flying = log.view(0, 100);
        assertEquals(List.of(new CallLog.InFlight(null, "a-Server-002", "kv", null, "script", 3_000)), flying.inFlight());
        assertEquals(List.of("peer"), flying.calls().stream().map(CallLog.Call::messageId).toList());

        log.serverFinished("a-Server-002", "script", "INVALID_REQUEST", null, 3_010);
        var client = log.view(flying.lastSeq(), 100).calls().getFirst();
        assertNull(client.source(), "A client's call has no server to come from");
        assertEquals("a-Server-002", client.target());
        assertEquals("kv", client.instruction());
        assertEquals("ERROR_REPLY", client.outcome());
        assertEquals("INVALID_REQUEST", client.detail());
        assertEquals(3_000, client.startMillis());
        assertEquals(3_010, client.endMillis());
    }

    @Test void aClientsRequestAbortedOrWithoutAMessageIdIsHandled() {
        log.serverRequest("a-Server-001", null, "kv", 1);
        log.serverFinished("a-Server-001", null, "OK", null, 2);
        assertTrue(log.view(0, 100).calls().isEmpty());
        log.serverRequest("a-Server-001", "m", "kv", 1);
        log.serverFinished("a-Server-002", "m", "OK", null, 2);
        assertTrue(log.view(0, 100).calls().isEmpty(), "Another server's reply does not end this request");
        log.serverFinished("a-Server-001", "m", null, "stopped", 3);
        var aborted = log.view(0, 100).calls().getFirst();
        assertEquals("FAILED", aborted.outcome());
        assertEquals("stopped", aborted.detail());
    }

    @Test void socketActivityReportsWhatServersLogToTheCallLog() {
        var activity = new SocketActivity();
        activity.reportTo(log);
        activity.accept("[2026-10-31 19:32:03.607 +08:00] Executing instruction: kv | connectionId=1 | client=127.0.0.1:50969 | serverId=a-Server-001 | messageId=m1", 5_000);
        activity.accept("[2026-10-31 19:32:03.616 +08:00] Command reply sent: OK | connectionId=1 | client=127.0.0.1:50969 | serverId=a-Server-001 | messageId=m1", 5_009);
        var seen = log.view(0, 100).calls().getFirst();
        assertEquals("a-Server-001", seen.target());
        assertEquals("OK", seen.outcome());
        assertEquals(5_000, seen.startMillis());
        assertEquals(5_009, seen.endMillis());
    }

    @Test void readingAfterASeqGivesOnlyWhatCameLaterOldestFirstAndTheNewestWhenLimited() {
        for (int i = 1; i <= 5; i++) {
            var call = call("a-Server-001", 9002, "m" + i);
            log.started(call);
            log.finished(call, PeerTraffic.Outcome.OK, null, 1);
        }
        log.mark("freeze", "a-Server-002");
        var all = log.view(0, 100);
        assertEquals(List.of("m1", "m2", "m3", "m4", "m5"), all.calls().stream().map(CallLog.Call::messageId).toList());
        assertEquals(1, all.firstSeq());
        var mark = all.marks().getFirst();
        assertEquals("freeze", mark.kind());
        assertEquals("a-Server-002", mark.text());
        assertEquals(all.lastSeq(), mark.seq());

        assertEquals(List.of("m4", "m5"), log.view(3, 100).calls().stream().map(CallLog.Call::messageId).toList());
        assertEquals(List.of("m4", "m5"), log.view(0, 2).calls().stream().map(CallLog.Call::messageId).toList());
        var nothingNew = log.view(all.lastSeq(), 100);
        assertTrue(nothingNew.calls().isEmpty());
        assertTrue(nothingNew.marks().isEmpty());
    }

    @Test void onlyTheNewestCallsAreKept() {
        int extra = 10;
        for (int i = 0; i < CallLog.CALLS_KEPT + extra; i++) {
            log.finished(call("a-Server-001", 9002, null), PeerTraffic.Outcome.OK, null, 1);
        }
        var view = log.view(0, Integer.MAX_VALUE);
        assertEquals(CallLog.CALLS_KEPT, view.calls().size());
        assertEquals(extra + 1, view.firstSeq());
    }

    @Test void aLongMarkIsCutShort() {
        log.mark("step", "x".repeat(1_000));
        assertEquals(200, log.view(0, 100).marks().getFirst().text().length());
    }
}
