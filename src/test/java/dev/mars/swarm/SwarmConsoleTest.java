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
    void virtualShutdownHookClosesServersDuringJvmExit() throws Exception {
        var javaExecutable = java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java.exe");
        if (!java.nio.file.Files.exists(javaExecutable)) {
            javaExecutable = java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java");
        }
        // A separate JVM inherits no system properties, so it is told not to announce on the network as well.
        var process = new ProcessBuilder(javaExecutable.toString(), "-Ddev.mars.announce=false", "-cp",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                ShutdownProbe.class.getName()).redirectErrorStream(true).start();
        try (var reader = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var output = reader.submit(() -> new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            try {
                assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS), "JVM shutdown did not complete");
                String actual = output.get(2, java.util.concurrent.TimeUnit.SECONDS);
                assertEquals(0, process.exitValue(), actual);
                assertTrue(actual.contains("Shutdown hook probe | virtual=true | state=NEW"), actual);
                assertTrue(actual.contains("ThreadPooledServer Closed."), "The shutdown hook must finish listener cleanup:\n" + actual);
                assertFalse(actual.contains("Server announcement sent"), "The probe's server must not announce itself:\n" + actual);
                var ports = Pattern.compile("Probe ports \\| main=(\\d+) \\| admin=(\\d+)").matcher(actual);
                assertTrue(ports.find(), actual);
                int main = Integer.parseInt(ports.group(1));
                int admin = Integer.parseInt(ports.group(2));
                try (var a = new java.net.ServerSocket(main); var b = new java.net.ServerSocket(admin)) {
                    assertEquals(main, a.getLocalPort());
                    assertEquals(admin, b.getLocalPort());
                }
                System.out.println("Virtual shutdown hook test PASSED | JVM exited after server cleanup");
            } finally {
                if (process.isAlive()) {
                    process.destroyForcibly();
                    assertTrue(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS));
                }
            }
        }
    }

    /** A separate JVM is required to exercise the actual Runtime shutdown sequence. */
    public static final class ShutdownProbe {
        public static void main(String[] args) throws Exception {
            var manager = new SwarmManager();
            var endpoint = manager.start("exit", 1).getFirst();
            var hook = SwarmMain.shutdownHook(manager);
            Runtime.getRuntime().addShutdownHook(hook);
            System.out.println("Shutdown hook probe | virtual=" + hook.isVirtual() + " | state=" + hook.getState());
            System.out.println("Probe ports | main=" + endpoint.port() + " | admin=" + endpoint.adminPort());
            // Ensure both listeners have started before exit, so abrupt VM termination cannot mimic cleanup.
            if (!manager.status("exit").getFirst().alive()) { throw new IllegalStateException("Server did not start"); }
            if (!manager.send("exit", "Ready for shutdown").getFirst().status().equals("OK")) {
                throw new IllegalStateException("Main listener did not process traffic");
            }
            System.exit(0);
        }
    }

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
    void serverCommandsStopAndRestartOneServerAndReportMisuse() throws Exception {
        String output = run("""
                start solo 2
                server stop solo-Server-002
                status solo
                server restart solo-Server-002
                status solo
                server leave solo-Server-001
                server stop missing-Server-001
                server explode solo-Server-001
                server stop
                quit
                """);
        assertTrue(output.contains("Server stopped | serverId=solo-Server-002"), output);
        assertTrue(output.contains("Server restarted | serverId=solo-Server-002"), output);
        var statuses = Pattern.compile("Swarm status \\| name=solo \\| serverId=solo-Server-002 [^\\r\\n]*alive=(true|false)").matcher(output);
        var alive = new java.util.ArrayList<String>();
        while (statuses.find()) { alive.add(statuses.group(1)); }
        assertEquals(java.util.List.of("false", "true"), alive, output);
        assertTrue(output.contains("Gossip is not enabled"), output);
        assertTrue(output.contains("Unknown server: missing-Server-001"), output);
        assertTrue(output.contains("Usage: server <stop|leave|restart|remove> <id>"), output);
    }

    @Test
    void addAndRemoveGrowAndShrinkASwarmAndReportMisuse() throws Exception {
        String output = run("""
                start grow 2
                add grow
                add grow 2
                list
                server remove grow-Server-002
                list
                add missing 1
                add grow x
                add
                server remove grow-Server-002
                quit
                """);
        assertTrue(output.contains("Servers added | name=grow | servers=1"), output);
        assertTrue(output.contains("Servers added | name=grow | servers=2"), output);
        assertTrue(output.contains("Server removed | serverId=grow-Server-002"), output);
        assertTrue(output.contains("serverId=grow-Server-005"), output);
        assertTrue(output.contains("Unknown swarm: missing"), output);
        assertTrue(output.contains("Usage: add <name> [servers]"), output);
        assertTrue(output.contains("Unknown server: grow-Server-002"), output);
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
