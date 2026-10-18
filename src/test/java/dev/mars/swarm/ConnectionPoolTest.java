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
            assertEquals(3, manager.connections().opened(), "Polling three servers three times opened one connection each, not nine");
            // The first poll of each server also asks whether the gossip plugin is there; the answer is remembered, so later polls send one request.
            assertEquals(9, manager.connections().reused(), "Per server: the plugin check on the first poll, then one ping on each of the two later polls");
        }
    }

    @Test
    void aLoadTestKeepsItsConnectionsOpenInsteadOfOpeningOnePerRequest() throws Exception {
        var activity = new SocketActivity();
        try (var ignored = activity.install(); var manager = new SwarmManager()) {
            manager.start("load", 2);
            var result = manager.test("load", 600, 10, done -> { });
            assertTrue(result.successful(), result.toString() + result.failures());
            long connections = activity.view("load-Server-001", 0, 1).counters().connections()
                    + activity.view("load-Server-002", 0, 1).counters().connections();
            long requests = activity.view("load-Server-001", 0, 1).counters().requests()
                    + activity.view("load-Server-002", 0, 1).counters().requests();
            assertEquals(600, requests);
            assertTrue(connections <= 60, "600 requests from 10 callers used " + connections + " connections");
        }
    }

    @Test
    void aServerThatRejectsTheKeepAliveFieldIsServedOneRequestPerConnectionFromThenOn() throws Exception {
        var requestsSeen = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var connectionsSeen = new java.util.concurrent.atomic.AtomicInteger();
        try (var workers = Executors.newVirtualThreadPerTaskExecutor();
             var listener = new java.net.ServerSocket(0, 50, java.net.InetAddress.getByAddress(new byte[] {127, 0, 0, 1}))) {
            // An older host: it does not know the "connection" field, and closes after every reply.
            workers.submit(() -> {
                while (!listener.isClosed()) {
                    var socket = listener.accept();
                    connectionsSeen.incrementAndGet();
                    workers.submit(() -> {
                        try (socket) {
                            var in = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
                            String line = in.readLine();
                            requestsSeen.add(line);
                            var request = dev.mars.protocol.Json.read(line);
                            var id = java.util.UUID.fromString(request.get("messageId").asText());
                            dev.mars.protocol.CommandReply reply = request.has("connection")
                                    ? dev.mars.protocol.CommandReply.failure(id, "old-Server-001", 1,
                                            dev.mars.protocol.CommandError.Code.INVALID_REQUEST, "Unknown field: connection")
                                    : new dev.mars.protocol.CommandReply(1, id, "old-Server-001", 1, "OK", "Received: hi", null);
                            socket.getOutputStream().write((dev.mars.protocol.Json.write(reply) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        } catch (Exception connectionEnded) { /* the client closed first */ }
                    });
                }
                return null;
            });
            var endpoint = new ServerEndpoint("old-Server-001", listener.getLocalPort(), 0);
            try (var pool = new ConnectionPool()) {
                var first = pool.execute(endpoint, "echo", Map.of("text", "hi"));
                assertEquals("OK", first.status(), "The request was sent again without the field");
                assertTrue(pool.oneRequestPerConnection(endpoint.port()));
                assertEquals(2, requestsSeen.size(), "One rejected request, then one that was served");
                var second = pool.execute(endpoint, "echo", Map.of("text", "hi"));
                assertEquals("OK", second.status());
                assertEquals(3, requestsSeen.size(), "No second attempt with the field once the server is known to reject it");
                assertFalse(requestsSeen.getLast().contains("connection"));
                assertEquals(3, connectionsSeen.get(), "Each request used its own connection, as the old host requires");
            }
        }
    }

    @Test
    void closingThePoolResetsIdleConnectionsSoTheLoadTestLeavesNothingInTimeWait() throws Exception {
        try (var manager = new SwarmManager()) {
            var endpoint = manager.start("reset", 1).getFirst();
            var pool = new ConnectionPool(4);
            for (int i = 0; i < 3; i++) { assertEquals("OK", pool.execute(endpoint, "echo", Map.of("text", "x")).status()); }
            assertEquals(1, pool.opened());
            pool.close();
            // After closing, a new pool must open a connection again: nothing is kept.
            try (var next = new ConnectionPool(4)) {
                assertEquals("OK", next.execute(endpoint, "echo", Map.of("text", "y")).status());
                assertEquals(1, next.opened());
            }
            assertThrows(IllegalArgumentException.class, () -> new ConnectionPool(0));
        }
    }
}
