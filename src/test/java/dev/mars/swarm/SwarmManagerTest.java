package dev.mars.swarm;

import dev.mars.server.ServerInstance;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(45)
class SwarmManagerTest {
    @Test
    @SuppressWarnings("unchecked")
    void aFrozenServerIsShownFrozenAndAnswersAgainOnceThawed() throws Exception {
        try (var manager = new SwarmManager()) {
            manager.start("ice", 2);
            manager.freezeServer("ice-Server-001");
            var servers = (List<Map<String, Object>>) ((List<Map<String, Object>>) SwarmSnapshot.take(manager).get("swarms")).getFirst().get("servers");
            assertEquals(Boolean.TRUE, servers.get(0).get("frozen"));
            assertEquals(Boolean.FALSE, servers.get(0).get("alive"));
            assertEquals("Frozen", servers.get(0).get("detail"));
            assertNull(servers.get(1).get("frozen"), "Only the frozen server says so");
            manager.thawServer("ice-Server-001");
            assertEquals("OK", manager.send("ice", "after the thaw").getFirst().status());
            assertThrows(IllegalArgumentException.class, () -> manager.freezeServer("ice-Server-009"));
        }
    }

    @Test
    void countsMixedHealthyAndUnreachableTargetsWithoutStoppingHealthyServers() throws Exception {
        var opened = new ConcurrentLinkedQueue<ServerInstance>();
        try (var manager = new SwarmManager(List.of(), options -> {
            var server = ServerInstance.start(options); opened.add(server); return server;
        })) {
            var endpoints = manager.start("mixed", 3);
            opened.stream().filter(server -> server.getServerId().equals("mixed-Server-002")).findFirst().orElseThrow().close();
            var progress = new ArrayList<Integer>();
            var result = manager.test("mixed", 12, 4, progress::add);
            assertEquals(8, result.passed());
            assertEquals(4, result.failed());
            assertEquals(4, result.failures().size());
            assertFalse(result.successful());
            assertEquals(List.of(12), progress);
            assertEquals(2, manager.status("mixed").stream().filter(ServerStatus::alive).count());
            manager.stop("mixed");
            assertReleased(endpoints);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 99, 101})
    void handlesSmallAndNonRoundRequestCountsWithFinalProgress(int requests) throws Exception {
        try (var manager = new SwarmManager()) {
            manager.start("progress", 2);
            var progress = new ArrayList<Integer>();
            var result = manager.test("progress", requests, 100, progress::add);
            assertTrue(result.successful(), result.toString());
            assertEquals(requests <= 100 ? List.of(requests) : List.of(100, requests), progress);
            assertEquals(requests, result.passed());
        }
    }

    @Test
    void forwardsAllRuntimeFoldersToEveryServer(@org.junit.jupiter.api.io.TempDir Path temporary) throws Exception {
        var first = java.nio.file.Files.createDirectory(temporary.resolve("first plugins"));
        var second = java.nio.file.Files.createDirectory(temporary.resolve("second plugins"));
        var supplied = new ArrayList<>(List.of(first, second));
        var observed = new ConcurrentLinkedQueue<List<Path>>();
        try (var manager = new SwarmManager(supplied, options -> {
            observed.add(options.pluginDirectories());
            return ServerInstance.start(options);
        })) {
            supplied.clear();
            manager.start("folders", 2);
            assertEquals(2, observed.size());
            assertTrue(observed.stream().allMatch(paths -> paths.equals(List.of(first, second))));
            assertTrue(manager.status("folders").stream().allMatch(ServerStatus::alive));
        }
    }

    @Test
    void managesIndependentSwarmsAndCorrelatesEveryReply() throws Exception {
        List<ServerEndpoint> all = new ArrayList<>();
        try (var manager = new SwarmManager()) {
            var alpha = manager.start("alpha", 3);
            var beta = manager.start("beta", 2);
            all.addAll(alpha); all.addAll(beta);
            assertEquals(List.of("alpha-Server-001", "alpha-Server-002", "alpha-Server-003"),
                    alpha.stream().map(ServerEndpoint::serverId).toList());
            var ports = new HashSet<Integer>();
            for (var endpoint : all) {
                assertTrue(endpoint.port() > 0); assertTrue(endpoint.adminPort() > 0);
                assertTrue(ports.add(endpoint.port())); assertTrue(ports.add(endpoint.adminPort()));
            }
            assertEquals(2, manager.list().size());
            assertThrows(UnsupportedOperationException.class, () -> manager.list().clear());
            assertTrue(manager.status("alpha").stream().allMatch(ServerStatus::alive));
            var replies = manager.send("alpha", "{\"instruction\":\"echo\",\"parameters\":{\"text\":\"Hello alpha\"}}");
            assertEquals(3, replies.size());
            var messages = new HashSet<java.util.UUID>();
            for (int i = 0; i < replies.size(); i++) {
                assertEquals(alpha.get(i).serverId(), replies.get(i).serverId());
                assertEquals("Received: Hello alpha", replies.get(i).result());
                assertTrue(messages.add(replies.get(i).messageId()));
            }
            assertEquals("Received: 你好", manager.send("beta", "你好").getFirst().result());
            manager.stop("alpha");
            assertReleased(alpha);
            assertTrue(manager.status("beta").stream().allMatch(ServerStatus::alive));
            assertEquals(1, manager.list().size());
            manager.stopAll();
            assertTrue(manager.list().isEmpty());
        }
        assertReleased(all);
    }

    @Test
    void verifiesOneThousandRequestsAcrossThreeServers() throws Exception {
        var progress = new ArrayList<Integer>();
        try (var manager = new SwarmManager()) {
            manager.start("load", 3);
            var result = manager.test("load", 1000, 100, progress::add);
            assertTrue(result.successful(), result.toString() + result.failures());
            assertEquals(1000, result.passed());
            assertEquals(0, result.failed());
            assertEquals(List.of(100, 200, 300, 400, 500, 600, 700, 800, 900, 1000), progress);
            assertTrue(manager.status("load").stream().allMatch(ServerStatus::alive));
            System.out.println(result);
        }
    }

    @Test
    void failedStartupRollsBackEveryNewInstanceAndPreservesOtherSwarms() throws Exception {
        var opened = new ConcurrentLinkedQueue<ServerInstance>();
        var attempts = new AtomicInteger();
        try (var manager = new SwarmManager(List.of(), options -> {
            if (options.serverId().startsWith("broken") && attempts.incrementAndGet() == 2) {
                throw new IOException("Deliberate startup failure");
            }
            var server = ServerInstance.start(options);
            if (options.serverId().startsWith("broken")) { opened.add(server); }
            return server;
        })) {
            manager.start("healthy", 1);
            var error = assertThrows(IOException.class, () -> manager.start("broken", 3));
            assertTrue(error.getMessage().contains("Deliberate startup failure"));
            assertEquals(2, opened.size());
            assertFalse(manager.list().containsKey("broken"));
            assertReleased(opened.stream().map(server -> new ServerEndpoint(server.getServerId(), server.getPort(), server.getAdminPort())).toList());
            assertTrue(manager.status("healthy").getFirst().alive());
        }
    }

    @Test
    void reportsUnreachableServersAndLoadFailures() throws Exception {
        var opened = new ArrayList<ServerInstance>();
        try (var manager = new SwarmManager(List.of(), options -> {
            var server = ServerInstance.start(options); opened.add(server); return server;
        })) {
            manager.start("offline", 1);
            opened.getFirst().close();
            assertFalse(manager.status("offline").getFirst().alive());
            var result = manager.test("offline", 12, 3, done -> { });
            assertFalse(result.successful());
            assertEquals(0, result.passed());
            assertEquals(12, result.failed());
            assertEquals(10, result.failures().size());
        }
    }

    @Test
    void missingPluginDirectoryDoesNotRegisterSwarm() throws Exception {
        try (var manager = new SwarmManager(Path.of("target", "missing-plugins-" + java.util.UUID.randomUUID()))) {
            assertThrows(IOException.class, () -> manager.start("missing", 2));
            assertTrue(manager.list().isEmpty());
        }
    }

    @Test
    void rejectsDuplicateAndUnknownNamesAndAllowsReuseAfterStop() throws Exception {
        try (var manager = new SwarmManager()) {
            manager.start("alpha", 1);
            assertThrows(IllegalArgumentException.class, () -> manager.start("alpha", 1));
            assertThrows(IllegalArgumentException.class, () -> manager.status("missing"));
            assertThrows(IllegalArgumentException.class, () -> manager.send("missing", "Hello"));
            assertThrows(IllegalArgumentException.class, () -> manager.stop("missing"));
            manager.stop("alpha");
            assertEquals(1, manager.start("alpha", 1).size());
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "two words", "1first", "bad|name"})
    void rejectsInvalidSwarmNames(String name) throws Exception {
        try (var manager = new SwarmManager()) {
            assertThrows(IllegalArgumentException.class, () -> manager.start(name, 1));
            assertTrue(manager.list().isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 101})
    void rejectsInvalidServerCounts(int count) throws Exception {
        try (var manager = new SwarmManager()) {
            assertThrows(IllegalArgumentException.class, () -> manager.start("alpha", count));
        }
    }

    @Test
    void validatesLoadSettingsAndCloseIsIdempotent() throws Exception {
        var manager = new SwarmManager();
        var endpoints = manager.start("alpha", 1);
        try (manager) {
            for (int requests : new int[]{-1, 0, 100001}) {
                assertThrows(IllegalArgumentException.class, () -> manager.test("alpha", requests, 1, done -> { }));
            }
            for (int clients : new int[]{-1, 0, 1001}) {
                assertThrows(IllegalArgumentException.class, () -> manager.test("alpha", 1, clients, done -> { }));
            }
        }
        manager.close();
        assertReleased(endpoints);
        assertThrows(IllegalStateException.class, () -> manager.start("closed", 1));
    }

    @Test
    void stopsAndRestartsOneServerOnItsSamePortsWithoutTouchingItsPeers() throws Exception {
        try (var manager = new SwarmManager()) {
            var endpoints = manager.start("solo", 3);
            var target = endpoints.get(1);
            manager.stopServer(target.serverId());
            var status = manager.status("solo");
            assertTrue(status.get(0).alive());
            assertFalse(status.get(1).alive());
            assertTrue(status.get(2).alive());
            assertEquals(target, manager.list().get("solo").get(1), "A stopped server stays listed with its ports");

            assertEquals(target, manager.restartServer(target.serverId()));
            assertTrue(manager.status("solo").stream().allMatch(ServerStatus::alive));
            var replies = manager.send("solo", "after restart");
            assertEquals(3, replies.size());
            assertTrue(replies.stream().allMatch(reply -> reply.result().equals("Received: after restart")));

            // Restarting a server that is still running replaces it.
            assertEquals(target, manager.restartServer(target.serverId()));
            assertTrue(manager.status("solo").stream().allMatch(ServerStatus::alive));
        }
    }

    @Test
    void serverOperationsRejectUnknownServersAndLeaveNeedsGossip() throws Exception {
        try (var manager = new SwarmManager()) {
            manager.start("solo", 2);
            for (String id : new String[]{null, "", "solo", "solo-Server-003", "solo-Server-000", "other-Server-001", "solo-Server-1"}) {
                assertThrows(IllegalArgumentException.class, () -> manager.stopServer(id), String.valueOf(id));
                assertThrows(IllegalArgumentException.class, () -> manager.restartServer(id), String.valueOf(id));
            }
            var error = assertThrows(IllegalStateException.class, () -> manager.leaveServer("solo-Server-001"));
            assertTrue(error.getMessage().contains("--gossip"));
            manager.stop("solo");
            assertThrows(IllegalArgumentException.class, () -> manager.stopServer("solo-Server-001"));
        }
        var closed = new SwarmManager();
        closed.start("gone", 1);
        closed.close();
        assertThrows(IllegalStateException.class, () -> closed.stopServer("gone-Server-001"));
        assertThrows(IllegalStateException.class, () -> closed.restartServer("gone-Server-001"));
    }

    @Test
    void theKeyValuePluginAnswersCommandsToOneServerAndEachServerKeepsItsOwnPairs() throws Exception {
        var plugin = Path.of("..", "socket-server-threadpool", "plugins", "kv-plugin", "target");
        boolean built = Files.isDirectory(plugin);
        if (built) { try (var files = Files.list(plugin)) { built = files.anyMatch(file -> file.getFileName().toString().endsWith(".jar")); } }
        Assumptions.assumeTrue(built, "Build the key-value plugin first: mvn -f ../socket-server-threadpool/plugins/kv-plugin/pom.xml package");
        try (var manager = new SwarmManager(List.of(plugin))) {
            manager.start("kv", 2);
            String one = "kv-Server-001", two = "kv-Server-002";

            var put = manager.command(one, "{\"instruction\":\"kv\",\"parameters\":{\"operation\":\"put\",\"key\":\"colour\",\"value\":\"blue\"}}");
            assertEquals(Boolean.TRUE, put.get("ok"), put.toString());
            assertEquals(Map.of("key", "colour", "created", true), put.get("result"));
            var object = manager.command(one, "{\"instruction\":\"kv\",\"parameters\":{\"operation\":\"put\",\"key\":\"limits\",\"value\":{\"pairs\":1000,\"strict\":true}}}");
            assertEquals(Boolean.TRUE, object.get("ok"), object.toString());

            var get = manager.command(one, "{\"instruction\":\"kv\",\"parameters\":{\"operation\":\"get\",\"key\":\"limits\"}}");
            @SuppressWarnings("unchecked") var found = (Map<String, Object>) get.get("result");
            assertEquals(Boolean.TRUE, found.get("found"));
            @SuppressWarnings("unchecked") var value = (Map<String, Object>) found.get("value");
            assertEquals(1000, ((Number) value.get("pairs")).intValue(), "The object comes back as it was stored");
            assertEquals(Boolean.TRUE, value.get("strict"));

            var list = manager.command(one, "{\"instruction\":\"kv\",\"parameters\":{\"operation\":\"list\"}}");
            @SuppressWarnings("unchecked") var listed = (Map<String, Object>) list.get("result");
            assertEquals(List.of("colour", "limits"), listed.get("keys"), "Keys come back in order");
            assertEquals(2, ((Number) listed.get("count")).intValue());

            var other = manager.command(two, "{\"instruction\":\"kv\",\"parameters\":{\"operation\":\"get\",\"key\":\"colour\"}}");
            assertEquals(Map.of("key", "colour", "found", false), other.get("result"), "Each server has its own store");

            var delete = manager.command(one, "{\"instruction\":\"kv\",\"parameters\":{\"operation\":\"delete\",\"key\":\"colour\"}}");
            assertEquals(Map.of("key", "colour", "deleted", true), delete.get("result"));
            var again = manager.command(one, "{\"instruction\":\"kv\",\"parameters\":{\"operation\":\"delete\",\"key\":\"colour\"}}");
            assertEquals(Map.of("key", "colour", "deleted", false), again.get("result"));

            var missing = manager.command(one, "{\"instruction\":\"kv\",\"parameters\":{\"operation\":\"put\",\"key\":\"x\"}}");
            assertEquals("ERROR", missing.get("status"));
            assertEquals("INVALID_PARAMETERS", missing.get("errorCode"));
            var unknown = manager.command(one, "{\"instruction\":\"kv\",\"parameters\":{\"operation\":\"explode\"}}");
            assertEquals("INVALID_PARAMETERS", unknown.get("errorCode"));
            assertTrue(unknown.get("errorMessage").toString().contains("Supported operations"), unknown.toString());
        }
        try (var manager = new SwarmManager()) {
            manager.start("plain", 1);
            var without = manager.command("plain-Server-001", "{\"instruction\":\"kv\",\"parameters\":{\"operation\":\"list\"}}");
            assertEquals("UNKNOWN_INSTRUCTION", without.get("errorCode"), "A server that did not load the plugin does not have the instruction");
        }
    }

    @Test
    void aCommandToOneServerReturnsTheReplyTheErrorOrWhyThereWasNone() throws Exception {
        try (var manager = new SwarmManager()) {
            manager.start("cmd", 2);

            var plain = manager.command("cmd-Server-002", "hello there");
            assertEquals(Boolean.TRUE, plain.get("ok"), plain.toString());
            assertEquals("OK", plain.get("status"));
            assertEquals("Received: hello there", plain.get("result"));
            assertEquals("cmd-Server-002", plain.get("replyServerId"));
            assertTrue(((Number) plain.get("connectionId")).longValue() > 0);
            assertTrue(plain.get("sent").toString().contains("\"instruction\":\"echo\""), "Plain text is sent as an echo command");
            assertTrue(plain.get("raw").toString().contains("\"status\":\"OK\""));
            assertNull(plain.get("failure"));

            var json = manager.command("cmd-Server-001", "{\"instruction\":\"echo\",\"parameters\":{\"text\":\"x\"}}");
            assertEquals("Received: x", json.get("result"));

            var unknown = manager.command("cmd-Server-001", "{\"instruction\":\"nope\",\"parameters\":{}}");
            assertEquals(Boolean.FALSE, unknown.get("ok"));
            assertEquals("ERROR", unknown.get("status"));
            assertEquals("UNKNOWN_INSTRUCTION", unknown.get("errorCode"));
            assertNotNull(unknown.get("errorMessage"));
            assertNull(unknown.get("failure"), "An error reply is a reply, not a failure");

            var invalid = manager.command("cmd-Server-001", "{\"instruction\":\"echo\",\"parameters\":{}}");
            assertEquals("ERROR", invalid.get("status"));
            assertEquals("INVALID_PARAMETERS", invalid.get("errorCode"));

            var malformed = manager.command("cmd-Server-001", "{not json");
            assertEquals("{not json", malformed.get("sent"), "Sent exactly as typed");
            assertTrue(malformed.get("note").toString().startsWith("Sent as typed"), malformed.toString());
            assertEquals("ERROR", malformed.get("status"), "The server's own complaint comes back: " + malformed);
            assertEquals("INVALID_REQUEST", malformed.get("errorCode"));

            manager.stopServer("cmd-Server-002");
            var stopped = manager.command("cmd-Server-002", "anyone there");
            assertEquals(Boolean.FALSE, stopped.get("ok"));
            assertEquals("FAILED", stopped.get("status"));
            assertTrue(stopped.get("failure").toString().contains("Connection refused"), stopped.toString());
            assertNull(stopped.get("raw"));

            assertThrows(IllegalArgumentException.class, () -> manager.command("cmd-Server-009", "x"));
            assertThrows(IllegalArgumentException.class, () -> manager.command("cmd-Server-001", "  "));
            assertThrows(IllegalArgumentException.class, () -> manager.command("cmd-Server-001", "a\nb"));
            assertThrows(IllegalArgumentException.class, () -> manager.command("cmd-Server-001", "x".repeat(20_000)));
        }
    }

    @Test
    void addingServersNumbersThemAfterTheHighestIdAndTheyAnswer() throws Exception {
        try (var manager = new SwarmManager()) {
            manager.start("grow", 2);
            var added = manager.addServers("grow", 2);
            assertEquals(List.of("grow-Server-003", "grow-Server-004"), added.stream().map(ServerEndpoint::serverId).toList());
            assertEquals(4, manager.list().get("grow").size());
            assertEquals(4, manager.status("grow").stream().filter(ServerStatus::alive).count());
            assertTrue(manager.send("grow", "hi").stream().allMatch(reply -> "OK".equals(reply.status())));

            // Numbers follow the highest ID the swarm still has, so removing the last server frees its number.
            manager.removeServer("grow-Server-004");
            assertEquals("grow-Server-004", manager.addServers("grow", 1).getFirst().serverId());
        }
    }

    @Test
    void removingAServerUnlistsItAndTheOthersKeepWorkingByTheirIds() throws Exception {
        try (var manager = new SwarmManager()) {
            var endpoints = manager.start("trim", 3);
            manager.removeServer("trim-Server-002");
            assertEquals(List.of("trim-Server-001", "trim-Server-003"),
                    manager.list().get("trim").stream().map(ServerEndpoint::serverId).toList());
            assertThrows(IllegalArgumentException.class, () -> manager.restartServer("trim-Server-002"), "A removed server cannot be restarted");
            assertThrows(IllegalArgumentException.class, () -> manager.removeServer("trim-Server-002"));
            assertThrows(java.io.IOException.class, () -> new java.net.Socket("localhost", endpoints.get(1).port()).close(), "Its port is closed");

            // Servers after the gap are still found by ID, not by position.
            manager.stopServer("trim-Server-003");
            manager.restartServer("trim-Server-003");
            assertTrue(manager.status("trim").stream().allMatch(ServerStatus::alive));
            assertEquals("trim-Server-004", manager.addServers("trim", 1).getFirst().serverId());

            manager.removeServer("trim-Server-001");
            manager.removeServer("trim-Server-003");
            manager.removeServer("trim-Server-004");
            assertFalse(manager.list().containsKey("trim"), "A swarm with no servers left is dropped");
        }
    }

    @Test
    void addingServersRejectsUnknownSwarmsAndSizesPastTheLimit() throws Exception {
        try (var manager = new SwarmManager()) {
            manager.start("cap", 99);
            assertThrows(IllegalArgumentException.class, () -> manager.addServers("nope", 1));
            assertThrows(IllegalArgumentException.class, () -> manager.addServers("cap", 0));
            assertThrows(IllegalArgumentException.class, () -> manager.addServers("cap", 2), "Would be 101");
            assertEquals(1, manager.addServers("cap", 1).size(), "Exactly 100 is allowed");
            assertEquals(100, manager.list().get("cap").size());
            assertThrows(IllegalArgumentException.class, () -> manager.addServers("cap", 1));
        }
    }

    @Test
    void failedRestartKeepsTheServerListedAndReportsWhy() throws Exception {
        var attempts = new AtomicInteger();
        try (var manager = new SwarmManager(List.of(), options -> {
            if (attempts.incrementAndGet() == 2) { throw new IOException("Deliberate restart failure"); }
            return ServerInstance.start(options);
        })) {
            var endpoint = manager.start("fragile", 1).getFirst();
            var error = assertThrows(IOException.class, () -> manager.restartServer(endpoint.serverId()));
            assertTrue(error.getMessage().contains("Deliberate restart failure"));
            assertEquals(List.of(endpoint), manager.list().get("fragile"));
            assertFalse(manager.status("fragile").getFirst().alive());
            manager.restartServer(endpoint.serverId());
            assertTrue(manager.status("fragile").getFirst().alive());
        }
    }

    private static void assertReleased(List<ServerEndpoint> endpoints) throws IOException {
        for (var endpoint : endpoints) {
            try (var main = new ServerSocket(endpoint.port()); var admin = new ServerSocket(endpoint.adminPort())) {
                assertEquals(endpoint.port(), main.getLocalPort());
                assertEquals(endpoint.adminPort(), admin.getLocalPort());
            }
        }
    }
}
