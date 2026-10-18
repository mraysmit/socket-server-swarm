package dev.mars.swarm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class SocketActivityTest {
    private static final String TAIL = " | connectionId=7 | client=127.0.0.1:50123 | server=ThreadPooledServer | serverId=alpha-Server-001 | messageId=%s | thread=42 | virtual=true";
    private static final String ID = "11111111-1111-4111-8111-111111111111";

    private static String line(String event, String messageId) {
        return "[2026-10-18 18:02:50.289 +08:00] " + event + String.format(TAIL, messageId);
    }

    @Test
    void groupsOneConnectionsLinesIntoConnectRequestAndReply() {
        var activity = new SocketActivity();
        activity.accept(line("Client connected", "-"), 1000);
        activity.accept(line("Executing instruction: echo", ID), 1001);
        activity.accept(line("Command reply sent: OK", ID), 1002);
        var view = activity.view("alpha-Server-001", 0, 100);
        assertEquals(3, view.lastSeq());
        assertEquals(1, view.firstSeq());
        var events = view.events();
        assertEquals(java.util.List.of("CONNECTED", "REQUEST", "REPLY"), events.stream().map(SocketActivity.Event::kind).toList());
        assertNull(events.get(0).messageId(), "A dash means no message ID yet");
        assertEquals("echo", events.get(1).instruction());
        assertEquals(ID, events.get(1).messageId());
        assertEquals("OK", events.get(2).status());
        assertEquals("7", events.get(2).connectionId());
        assertEquals("127.0.0.1:50123", events.get(2).client());
        assertEquals(1002, events.get(2).timeMillis());
        var counters = view.counters();
        assertEquals(1, counters.connections());
        assertEquals(1, counters.requests());
        assertEquals(1, counters.repliesOk());
        assertEquals(0, counters.repliesError());
        assertEquals(java.util.Map.of("echo", 1L), counters.byInstruction());
    }

    @Test
    void countsErrorsAbortsAndInstructionsAndIgnoresOtherLines() {
        var activity = new SocketActivity();
        activity.accept(line("Executing instruction: gossip", ID), 1);
        activity.accept(line("Command reply sent: ERROR", ID), 2);
        activity.accept(line("Executing instruction: gossip", ID), 3);
        activity.accept("Request aborted: Read timed out | connectionId=9 | serverId=alpha-Server-001", 4);
        activity.accept("Command received: status", 5);
        activity.accept("Server ready | serverId=alpha-Server-001 | main=localhost:1 | admin=localhost:2 | instructions=[echo]", 6);
        activity.accept("ThreadPooledServer Closed.", 7);
        activity.accept(null, 8);
        activity.accept("", 9);
        var view = activity.view("alpha-Server-001", 0, 100);
        assertEquals(4, view.events().size());
        assertEquals(2, view.counters().requests());
        assertEquals(1, view.counters().repliesError());
        assertEquals(1, view.counters().aborted());
        assertEquals(java.util.Map.of("gossip", 2L), view.counters().byInstruction());
        var aborted = view.events().getLast();
        assertEquals("ABORTED", aborted.kind());
        assertEquals("Read timed out", aborted.detail());
        assertEquals("9", aborted.connectionId());
    }

    @Test
    void keepsServersApartAndOnlyTheNewestEventsPerServer() {
        var activity = new SocketActivity();
        for (int i = 0; i < SocketActivity.EVENTS_PER_SERVER + 50; i++) { activity.accept(line("Client connected", "-"), i); }
        activity.accept(line("Client connected", "-").replace("alpha-Server-001", "beta-Server-001"), 1);
        assertNull(activity.view("gamma-Server-001", 0, 10));
        var view = activity.view("alpha-Server-001", 0, 1000);
        assertEquals(SocketActivity.EVENTS_PER_SERVER, view.events().size());
        assertEquals(SocketActivity.EVENTS_PER_SERVER + 50, view.lastSeq());
        assertEquals(51, view.firstSeq(), "The oldest 50 events were dropped");
        assertEquals(SocketActivity.EVENTS_PER_SERVER + 50, view.counters().connections(), "Counters keep counting past the buffer");
        assertEquals(1, activity.view("beta-Server-001", 0, 10).counters().connections());
    }

    @Test
    void viewReturnsOnlyEventsAfterAGivenSequenceAndHonoursTheLimit() {
        var activity = new SocketActivity();
        for (int i = 0; i < 10; i++) { activity.accept(line("Client connected", "-"), i); }
        var after = activity.view("alpha-Server-001", 7, 100);
        assertEquals(java.util.List.of(8L, 9L, 10L), after.events().stream().map(SocketActivity.Event::seq).toList());
        var limited = activity.view("alpha-Server-001", 0, 3);
        assertEquals(java.util.List.of(8L, 9L, 10L), limited.events().stream().map(SocketActivity.Event::seq).toList(), "The newest events win");
        assertTrue(activity.view("alpha-Server-001", 10, 100).events().isEmpty());
    }

    @Test
    void installCopiesConsoleOutputToTheRecorderAndRestoresTheStreams() throws Exception {
        var original = System.out;
        var originalErr = System.err;
        var captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        var activity = new SocketActivity();
        try {
            try (var ignored = activity.install()) {
                System.out.println(line("Executing instruction: echo", ID));
                System.out.print("partial ");
                System.out.println("line | serverId=other-Server-002");
                System.err.println("Request aborted: reset | connectionId=3 | serverId=alpha-Server-001");
                assertNotSame(original, System.out);
            }
            assertSame(originalErr, System.err);
            String passedThrough = captured.toString(StandardCharsets.UTF_8);
            assertTrue(passedThrough.contains("Executing instruction: echo"), "Output still reaches the console");
            assertTrue(passedThrough.contains("partial line"));
            var view = activity.view("alpha-Server-001", 0, 10);
            assertEquals(2, view.events().size());
            assertEquals("REQUEST", view.events().get(0).kind());
            assertEquals("ABORTED", view.events().get(1).kind());
            assertNull(activity.view("other-Server-002", 0, 10), "A line that is not a socket event records nothing");
        } finally {
            System.setOut(original);
        }
    }

    @Test
    void recordsRealServerTrafficFromTheirLogLines() throws Exception {
        var activity = new SocketActivity();
        try (var ignored = activity.install(); var manager = new SwarmManager()) {
            manager.start("real", 2);
            manager.send("real", "hello");
            manager.send("real", "{\"instruction\":\"echo\",\"parameters\":{\"text\":\"again\"}}");
            var view = activity.view("real-Server-002", 0, 100);
            assertNotNull(view);
            assertEquals(2, view.counters().requests());
            assertEquals(2, view.counters().repliesOk());
            assertEquals(java.util.Map.of("echo", 2L), view.counters().byInstruction());
            assertTrue(view.events().stream().anyMatch(event -> event.kind().equals("REQUEST") && event.messageId() != null));
            assertTrue(view.events().stream().allMatch(event -> event.client() != null && event.client().startsWith("127.0.0.1:")));
        }
    }
}
