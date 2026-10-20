package dev.mars.swarm;

import dev.mars.protocol.Json;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real servers with the real gossip and key-value plugins. They are skipped when the plugins have not been built in the
 * sibling socket-server-threadpool project.
 */
@Timeout(120)
class SwarmKvReplicationTest {
    private static final Path GOSSIP = Path.of("..", "socket-server-threadpool", "plugins", "gossip-plugin", "target");
    private static final Path KV = Path.of("..", "socket-server-threadpool", "plugins", "kv-plugin", "target");
    private static final long DEADLINE_MILLIS = 30_000;

    private static void requirePlugins() throws IOException {
        for (var folder : List.of(GOSSIP, KV)) {
            boolean built = Files.isDirectory(folder);
            if (built) { try (var files = Files.list(folder)) { built = files.anyMatch(file -> file.getFileName().toString().endsWith(".jar")); } }
            Assumptions.assumeTrue(built, "Build the plugin first: mvn -f ../socket-server-threadpool/plugins/<name>/pom.xml package (" + folder + ")");
        }
    }

    private static SwarmManager swarm(boolean kvReplicate) {
        return new SwarmManager(List.of(GOSSIP, KV), GossipSettings.fast(), kvReplicate);
    }

    /** Sends one kv command to one server and returns the result it replied with. */
    private static Map<String, Object> kv(SwarmManager manager, String serverId, Map<String, Object> parameters) throws IOException {
        var request = new LinkedHashMap<String, Object>();
        request.put("instruction", "kv");
        request.put("parameters", parameters);
        var outcome = manager.command(serverId, Json.write(request));
        assertEquals(Boolean.TRUE, outcome.get("ok"), outcome.toString());
        @SuppressWarnings("unchecked") var result = (Map<String, Object>) outcome.get("result");
        return result;
    }

    private static boolean found(SwarmManager manager, String serverId, String key) {
        try { return Boolean.TRUE.equals(kv(manager, serverId, Map.of("operation", "get", "key", key)).get("found")); }
        catch (IOException | AssertionError e) { return false; }
    }

    /** Waits until every server rates every member ALIVE, itself included (its members list has a row for itself), so a push reaches all of them. */
    private static void awaitGossipConverged(SwarmManager manager, List<ServerEndpoint> servers) throws Exception {
        await("every server rates all the others ALIVE", () -> servers.stream().allMatch(server -> {
            try {
                var outcome = manager.command(server.serverId(), "{\"instruction\":\"gossip\",\"parameters\":{\"operation\":\"members\"}}");
                @SuppressWarnings("unchecked") var result = (Map<String, Object>) outcome.get("result");
                @SuppressWarnings("unchecked") var members = (List<Map<String, Object>>) result.get("members");
                return members.stream().filter(member -> "ALIVE".equals(member.get("assessment"))).count() >= servers.size();
            } catch (RuntimeException e) { return false; }
        }));
    }

    private static void await(String what, BooleanSupplier condition) throws Exception {
        long until = System.currentTimeMillis() + DEADLINE_MILLIS;
        while (System.currentTimeMillis() < until) {
            try { if (condition.getAsBoolean()) { return; } } catch (RuntimeException notYet) { /* try again */ }
            Thread.sleep(200);
        }
        fail("Timed out waiting until " + what);
    }

    private static boolean onEveryServer(SwarmManager manager, List<ServerEndpoint> servers, String key) {
        return servers.stream().allMatch(server -> found(manager, server.serverId(), key));
    }

    @Test
    void aWriteOnEachOfFiveServersBecomesReadableOnAllFive() throws Exception {
        requirePlugins();
        try (var manager = swarm(true)) {
            var servers = manager.start("kv", 5);
            awaitGossipConverged(manager, servers);
            for (var server : servers) {
                kv(manager, server.serverId(), Map.of("operation", "put", "key", "from-" + server.serverId(), "value", "hello from " + server.serverId()));
            }
            for (var server : servers) {
                await("the pair written on " + server.serverId() + " to be readable everywhere",
                        () -> onEveryServer(manager, servers, "from-" + server.serverId()));
            }
            var value = kv(manager, servers.get(3).serverId(), Map.of("operation", "get", "key", "from-" + servers.get(0).serverId()));
            assertEquals("hello from " + servers.get(0).serverId(), value.get("value"));
        }
    }

    @Test
    void aDeleteOnOneServerIsSeenOnAllFive() throws Exception {
        requirePlugins();
        try (var manager = swarm(true)) {
            var servers = manager.start("kv", 5);
            awaitGossipConverged(manager, servers);
            kv(manager, servers.get(0).serverId(), Map.of("operation", "put", "key", "gone-soon", "value", "1"));
            await("the pair to be readable everywhere", () -> onEveryServer(manager, servers, "gone-soon"));
            kv(manager, servers.get(2).serverId(), Map.of("operation", "delete", "key", "gone-soon"));
            await("the deletion to reach every server", () -> servers.stream().noneMatch(server -> found(manager, server.serverId(), "gone-soon")));
        }
    }

    @Test
    void anObjectValueSurvivesTheJourneyBetweenRealServers() throws Exception {
        requirePlugins();
        try (var manager = swarm(true)) {
            var servers = manager.start("kv", 3);
            awaitGossipConverged(manager, servers);
            var value = Map.of("name", "node", "ports", List.of(9000, 9001), "nested", Map.of("live", true));
            kv(manager, servers.get(0).serverId(), Map.of("operation", "put", "key", "config", "value", value));
            await("the pair to be readable everywhere", () -> onEveryServer(manager, servers, "config"));
            var read = kv(manager, servers.get(2).serverId(), Map.of("operation", "get", "key", "config"));
            assertEquals(Json.write(value), Json.write(read.get("value")));
        }
    }

    @Test
    void statusOnTheWritingServerShowsReplicationOnAndItsPushes() throws Exception {
        requirePlugins();
        try (var manager = swarm(true)) {
            var servers = manager.start("kv", 3);
            awaitGossipConverged(manager, servers);
            kv(manager, servers.get(0).serverId(), Map.of("operation", "put", "key", "a", "value", "1"));
            await("two pushes to be counted", () -> {
                try {
                    var status = kv(manager, servers.get(0).serverId(), Map.of("operation", "status"));
                    @SuppressWarnings("unchecked") var counters = (Map<String, Object>) status.get("counters");
                    return "on".equals(status.get("replication")) && ((Number) counters.get("pushed")).longValue() >= 2;
                } catch (IOException e) { return false; }
            });
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void theSnapshotCarriesEachServersKvStatusAndTheSwarmsInSyncSummary() throws Exception {
        requirePlugins();
        try (var manager = swarm(true)) {
            var servers = manager.start("kv", 3);
            awaitGossipConverged(manager, servers);
            kv(manager, servers.get(0).serverId(), Map.of("operation", "put", "key", "a", "value", "1"));
            await("the snapshot to show all three servers in sync", () -> {
                var swarm = ((List<Map<String, Object>>) SwarmSnapshot.take(manager).get("swarms")).getFirst();
                var summary = (Map<String, Object>) swarm.get("kvSync");
                return summary != null && Integer.valueOf(3).equals(summary.get("inSync")) && Integer.valueOf(3).equals(summary.get("replicating"));
            });
            var swarm = ((List<Map<String, Object>>) SwarmSnapshot.take(manager).get("swarms")).getFirst();
            var roots = new java.util.HashSet<Object>();
            for (var server : (List<Map<String, Object>>) swarm.get("servers")) {
                var status = (Map<String, Object>) server.get("kv");
                assertEquals("on", status.get("replication"));
                assertEquals(1, ((Number) status.get("pairs")).intValue());
                assertNotNull(status.get("sync"));
                roots.add(status.get("root"));
            }
            assertEquals(1, roots.size(), "Every server holds the same root");
        }
    }

    @Test
    void aServerAddedToARunningSwarmReceivesEverythingThatWasWrittenBeforeItJoined() throws Exception {
        requirePlugins();
        try (var manager = swarm(true)) {
            var servers = manager.start("kv", 3);
            awaitGossipConverged(manager, servers);
            for (int i = 0; i < 25; i++) { kv(manager, servers.get(i % 3).serverId(), Map.of("operation", "put", "key", "before-" + i, "value", i)); }
            await("the first servers to share everything", () -> onEveryServer(manager, servers, "before-24"));
            var added = manager.addServers("kv", 1).getFirst();
            await("the added server to hold every earlier pair", () -> {
                for (int i = 0; i < 25; i++) { if (!found(manager, added.serverId(), "before-" + i)) { return false; } }
                return true;
            });
        }
    }

    @Test
    void aServerThatWasStoppedAndRestartedHasWhatItMissedAndWhatItHadBefore() throws Exception {
        requirePlugins();
        try (var manager = swarm(true)) {
            var servers = manager.start("kv", 3);
            awaitGossipConverged(manager, servers);
            String third = servers.get(2).serverId();
            kv(manager, servers.get(0).serverId(), Map.of("operation", "put", "key", "before", "value", "1"));
            await("the pair to reach everyone", () -> onEveryServer(manager, servers, "before"));
            manager.stopServer(third);
            kv(manager, servers.get(0).serverId(), Map.of("operation", "put", "key", "during", "value", "2"));
            kv(manager, servers.get(1).serverId(), Map.of("operation", "delete", "key", "before"));
            manager.restartServer(third);
            await("the restarted server to catch up", () -> found(manager, third, "during") && !found(manager, third, "before"));
        }
    }

    @Test
    void twoServersWritingTheSameKeyAtOnceAgreeOnTheValueEverywhere() throws Exception {
        requirePlugins();
        try (var manager = swarm(true)) {
            var servers = manager.start("kv", 4);
            awaitGossipConverged(manager, servers);
            var one = servers.get(0).serverId();
            var two = servers.get(3).serverId();
            kv(manager, one, Map.of("operation", "put", "key", "contested", "value", "from-one"));
            kv(manager, two, Map.of("operation", "put", "key", "contested", "value", "from-two"));
            await("every server to hold the same value", () -> {
                var values = new java.util.HashSet<Object>();
                for (var server : servers) {
                    try { values.add(kv(manager, server.serverId(), Map.of("operation", "get", "key", "contested")).get("value")); }
                    catch (IOException e) { return false; }
                }
                return values.size() == 1;
            });
            assertEquals("from-two", kv(manager, one, Map.of("operation", "get", "key", "contested")).get("value"), "The later write wins");
        }
    }

    @Test
    void withoutKvReplicationAWriteStaysOnTheServerThatTookIt() throws Exception {
        requirePlugins();
        try (var manager = swarm(false)) {
            var servers = manager.start("plain", 3);
            awaitGossipConverged(manager, servers);
            kv(manager, servers.get(0).serverId(), Map.of("operation", "put", "key", "private", "value", "1"));
            Thread.sleep(2_000);
            assertTrue(found(manager, servers.get(0).serverId(), "private"));
            assertFalse(found(manager, servers.get(1).serverId(), "private"));
            assertFalse(found(manager, servers.get(2).serverId(), "private"));
            var status = kv(manager, servers.get(0).serverId(), Map.of("operation", "status"));
            assertEquals("off", status.get("replication"));
        }
    }
}
