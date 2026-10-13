package dev.mars.swarm;

import dev.mars.server.ServerInstance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(45)
class SwarmManagerTest {
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
            var replies = manager.send("alpha", "{\"instruction\":\"math.add\",\"parameters\":{\"a\":10,\"b\":20}}");
            assertEquals(3, replies.size());
            var messages = new HashSet<java.util.UUID>();
            for (int i = 0; i < replies.size(); i++) {
                assertEquals(alpha.get(i).serverId(), replies.get(i).serverId());
                assertEquals("30", replies.get(i).result().toString());
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
        try (var manager = new SwarmManager(null, options -> {
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
        try (var manager = new SwarmManager(null, options -> {
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

    private static void assertReleased(List<ServerEndpoint> endpoints) throws IOException {
        for (var endpoint : endpoints) {
            try (var main = new ServerSocket(endpoint.port()); var admin = new ServerSocket(endpoint.adminPort())) {
                assertEquals(endpoint.port(), main.getLocalPort());
                assertEquals(endpoint.adminPort(), admin.getLocalPort());
            }
        }
    }
}
