package dev.mars.swarm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class SwarmConsoleTest {
    @Test
    void acceptsRepeatedRuntimePluginFoldersAndRejectsMissingValues() {
        assertTrue(SwarmMain.parsePluginDirectories().isEmpty());
        assertEquals(java.util.List.of(java.nio.file.Path.of("first plugins"), java.nio.file.Path.of("second")),
                SwarmMain.parsePluginDirectories("--plugins-dir", "first plugins", "--plugins-dir", "second"));
        assertThrows(UnsupportedOperationException.class, () -> SwarmMain.parsePluginDirectories().clear());
        for (String[] args : java.util.List.of(new String[]{"--plugins-dir"}, new String[]{"--plugins-dir", " "},
                new String[]{"--unknown", "folder"}, new String[]{"--plugins-dir", "--plugins-dir", "second"})) {
            assertThrows(IllegalArgumentException.class, () -> SwarmMain.parsePluginDirectories(args));
        }
    }

    @Test
    void consoleManagesTwoSwarmsSendsCommandsAndShowsTestProgress() throws Exception {
        String output = run("""
                start alpha 2
                start beta 1
                list
                status alpha
                send alpha {"instruction":"echo","parameters":{"text":"Hello alpha"}}
                send beta Hello beta
                test alpha 20 5
                stop alpha
                status beta
                stop-all
                list
                quit
                """);
        assertTrue(output.contains("Swarm started | name=alpha | servers=2"), output);
        var reply = Pattern.compile("Swarm reply \\| messageId=([^ ]+) \\| serverId=([^ ]+)"
                + " \\| connectionId=(\\d+) \\| status=([^ ]+) \\| result=([^\\r\\n]+)").matcher(output);
        var results = new HashMap<String, String>();
        var messages = new HashSet<UUID>();
        while (reply.find()) {
            assertTrue(messages.add(UUID.fromString(reply.group(1))), "Each server request must use a different message ID");
            assertTrue(Long.parseLong(reply.group(3)) > 0, output);
            assertEquals("OK", reply.group(4), output);
            assertNull(results.put(reply.group(2), reply.group(5)), "Unexpected duplicate reply from a server");
        }
        assertEquals(Map.of("alpha-Server-001", "\"Received: Hello alpha\"",
                "alpha-Server-002", "\"Received: Hello alpha\"", "beta-Server-001", "\"Received: Hello beta\""), results, output);
        assertTrue(output.contains("completed=20/20"), output);
        assertTrue(output.contains("Swarm test PASSED | swarm=alpha | requests=20 | passed=20 | failed=0"), output);
        var status = Pattern.compile("Swarm status \\| name=([^ ]+) \\| serverId=([^ ]+)"
                + " \\| main=localhost:\\d+ \\| admin=localhost:\\d+ \\| alive=(true|false)").matcher(output);
        var liveServers = new HashSet<String>();
        while (status.find()) {
            assertEquals("true", status.group(3), output);
            assertTrue(liveServers.add(status.group(2)), "Unexpected duplicate server status");
            if (status.group(1).equals("beta")) {
                int stopped = output.indexOf("Swarm stopped | name=alpha");
                assertTrue(stopped >= 0 && stopped < status.start(), "Beta must remain healthy after alpha stops");
            }
        }
        assertEquals(Set.of("alpha-Server-001", "alpha-Server-002", "beta-Server-001"), liveServers, output);
        assertTrue(output.contains("All swarms stopped"), output);
        assertTrue(output.contains("No running swarms"), output);
        assertFalse(output.contains("Error:"), output);
        System.out.println("Swarm console test PASSED");
    }

    @Test
    void invalidCommandsReportErrorsAndConsoleContinues() throws Exception {
        String output = run("""
                start alpha nope
                start alpha
                status missing
                test missing 0
                send alpha
                nonsense
                help
                quit extra
                list
                quit
                """);
        assertTrue(output.contains("Server count must be an integer"), output);
        assertTrue(output.contains("Usage: start"), output);
        assertTrue(output.contains("Unknown swarm: missing"), output);
        assertTrue(output.contains("Requests must be between"), output);
        assertTrue(output.contains("Usage: send"), output);
        assertTrue(output.contains("Unknown command; enter help"), output);
        assertTrue(output.contains("Usage: quit"), output);
        assertTrue(output.contains("No running swarms"), output);
    }

    @Test
    void eofReturnsAndOwnerClosesSwarms() throws Exception {
        var manager = new SwarmManager();
        try (manager) {
            SwarmMain.runConsole(manager, new BufferedReader(new StringReader("start eof 1\n")),
                    new PrintWriter(new StringWriter(), true));
            assertEquals(1, manager.list().size());
        }
        assertTrue(manager.list().isEmpty());
    }

    private static String run(String input) throws Exception {
        var output = new StringWriter();
        try (var manager = new SwarmManager()) {
            SwarmMain.runConsole(manager, new BufferedReader(new StringReader(input)), new PrintWriter(output, true));
        }
        return output.toString();
    }
}
