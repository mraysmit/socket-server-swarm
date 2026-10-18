package dev.mars.swarm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class ConnectionPoolTest {
    @Test
    void successiveCommandsToOneServerShareAConnection() throws Exception {
        var activity = new SocketActivity();
        try (var ignored = activity.install(); var manager = new SwarmManager()) {
            var endpoint = manager.start("pool", 1).getFirst();
            for (int i = 0; i < 10; i++) {
                var reply = manager.execute(endpoint, "echo", Map.of("text", "n" + i));
                assertEquals("OK", reply.status());
                assertEquals("Received: n" + i, reply.result());
                assertEquals("pool-Server-001", reply.serverId());
            }
            assertEquals(1, manager.connections().opened());
            assertEquals(9, manager.connections().reused());
            var counters = activity.view("pool-Server-001", 0, 100).counters();
            assertEquals(1, counters.connections(), "The server saw one connection");
            assertEquals(10, counters.requests());
        }
    }

    @Test
    void anErrorReplyIsReturnedAsAValueAndTheConnectionStaysUsable() throws Exception {
        try (var manager = new SwarmManager()) {
            var endpoint = manager.start("pool", 1).getFirst();
            var error = manager.execute(endpoint, "no.such.instruction", Map.of());
            assertEquals("ERROR", error.status());
            assertEquals("UNKNOWN_INSTRUCTION", error.error().code().name());
            assertEquals("Received: after", manager.execute(endpoint, "echo", Map.of("text", "after")).result());
            assertEquals(1, manager.connections().opened(), "The error did not cost the connection");
        }
    }

    @Test
    void aRestartedServerIsReachedAgainBecauseAStaleConnectionIsRetriedOnANewOne() throws Exception {
        try (var manager = new SwarmManager()) {
            var endpoint = manager.start("pool", 1).getFirst();
            manager.execute(endpoint, "echo", Map.of("text", "before"));
            manager.restartServer(endpoint.serverId());
            assertEquals("Received: after", manager.execute(endpoint, "echo", Map.of("text", "after")).result());
            assertEquals(2, manager.connections().opened(), "A new connection replaced the one the restart closed");
        }
    }

    @Test
    void aStoppedServerIsReportedAsAFailureNotRetriedForever() throws Exception {
        try (var manager = new SwarmManager()) {
            var endpoint = manager.start("pool", 1).getFirst();
            manager.execute(endpoint, "echo", Map.of("text", "up"));
            manager.stopServer(endpoint.serverId());
            assertThrows(IOException.class, () -> manager.execute(endpoint, "echo", Map.of("text", "down")));
        }
    }

    @Test
    void concurrentCommandsEachGetTheirOwnConnectionAndTheirOwnReply() throws Exception {
        try (var manager = new SwarmManager(); var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var endpoint = manager.start("pool", 1).getFirst();
            var futures = new ArrayList<java.util.concurrent.Future<String>>();
            for (int i = 0; i < 20; i++) {
                int worker = i;
                futures.add(workers.submit(() -> {
                    String last = null;
                    for (int n = 0; n < 15; n++) {
                        String text = "w" + worker + "-" + n;
                        last = (String) manager.execute(endpoint, "echo", Map.of("text", text)).result();
                        assertEquals("Received: " + text, last, "Each reply answers its own request");
                    }
                    return last;
                }));
            }
            for (var future : futures) { assertNotNull(future.get()); }
            // 20 callers at once need up to 20 connections, but only four idle ones are kept per server, so some are reopened.
            assertTrue(manager.connections().opened() <= 100, "Opened " + manager.connections().opened() + " connections for 300 requests");
            assertTrue(manager.connections().reused() >= 200, "Reused " + manager.connections().reused());
        }
    }

    @Test
    void theServerSnapshotOfAGossipFreeSwarmStillWorksOverThePool() throws Exception {
        try (var manager = new SwarmManager()) {
            manager.start("pool", 3);
            for (int i = 0; i < 3; i++) {
                var snapshot = SwarmSnapshot.take(manager);
                @SuppressWarnings("unchecked") var swarms = (java.util.List<Map<String, Object>>) snapshot.get("swarms");
                @SuppressWarnings("unchecked") var servers = (java.util.List<Map<String, Object>>) swarms.getFirst().get("servers");
                assertEquals(3, servers.size());
                assertTrue(servers.stream().allMatch(server -> Boolean.TRUE.equals(server.get("alive"))));
                assertTrue(servers.stream().allMatch(server -> server.get("gossip") == null));
            }
        }
    }
}
