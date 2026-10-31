package dev.mars.swarm;

import dev.mars.protocol.Json;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real servers with the real Raft plugin, loaded as a harness loads it. Skipped when the plugin has not been built in the sibling
 * socket-server-threadpool project: mvn -f ../socket-server-threadpool/plugins/raft-plugin/pom.xml -DskipTests package
 */
@Timeout(120)
class SwarmRaftTest {
    private static final Path RAFT = Path.of("..", "socket-server-threadpool", "plugins", "raft-plugin", "target");
    private static final Path GOSSIP = Path.of("..", "socket-server-threadpool", "plugins", "gossip-plugin", "target");
    private static final Path KV = Path.of("..", "socket-server-threadpool", "plugins", "kv-plugin", "target");
    private static final long DEADLINE_MILLIS = 30_000;

    @BeforeAll static void requirePlugin() throws IOException {
        for (var folder : List.of(RAFT, GOSSIP, KV)) {
            boolean built = Files.isDirectory(folder);
            if (built) { try (var files = Files.list(folder)) { built = files.anyMatch(file -> file.getFileName().toString().endsWith(".jar")); } }
            Assumptions.assumeTrue(built, "Build the plugin first: " + folder);
        }
    }

    private static SwarmManager raftOnly() { return new SwarmManager(List.of(RAFT)); }

    private static void await(String what, BooleanSupplier condition) throws Exception {
        long until = System.currentTimeMillis() + DEADLINE_MILLIS;
        while (System.currentTimeMillis() < until) {
            if (condition.getAsBoolean()) { return; }
            Thread.sleep(20);
        }
        fail("Timed out waiting: " + what);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> raft(SwarmManager manager, String serverId, Map<String, Object> parameters) throws IOException {
        var request = new LinkedHashMap<String, Object>();
        request.put("instruction", "raft");
        request.put("parameters", parameters);
        var outcome = manager.command(serverId, Json.write(request));
        assertEquals(Boolean.TRUE, outcome.get("ok"), outcome.toString());
        return (Map<String, Object>) outcome.get("result");
    }

    private static Map<String, Object> status(SwarmManager manager, String serverId) {
        try { return raft(manager, serverId, Map.of("operation", "status")); }
        catch (IOException | AssertionError notAnswering) { return Map.of(); }
    }

    /** The one server that says it leads, among those given. */
    private static String leaderAmong(SwarmManager manager, List<ServerEndpoint> servers) throws Exception {
        var found = new String[1];
        await("one server leads", () -> {
            for (var server : servers) {
                if ("LEADER".equals(status(manager, server.serverId()).get("role"))) {
                    found[0] = server.serverId();
                    return true;
                }
            }
            return false;
        });
        return found[0];
    }

    private static Map<String, Object> propose(SwarmManager manager, String serverId, String note) throws IOException {
        return raft(manager, serverId, Map.of("operation", "propose", "command", Map.of("instruction", "kv", "note", note)));
    }

    @Test void aRaftSwarmStartsBootstrappedElectsALeaderAndCommits() throws Exception {
        try (var manager = raftOnly()) {
            var servers = manager.startRaft("consensus", 3);
            assertEquals(3, servers.size());
            assertTrue(manager.isRaft("consensus"));
            var leader = leaderAmong(manager, servers);
            var answer = propose(manager, leader, "hello");
            assertEquals("COMMITTED", answer.get("status"), answer.toString());
            for (var server : servers) { assertEquals("running", status(manager, server.serverId()).get("state")); }
        }
    }

    @Test void itRunsWithNeitherGossipNorKvLoaded() throws Exception {
        try (var manager = raftOnly()) {
            var servers = manager.startRaft("alone", 1);
            leaderAmong(manager, servers);
            assertEquals(List.of("echo", "raft"), manager.instructions(servers.getFirst().serverId()).stream().sorted().toList());
        }
    }

    @Test void theSnapshotShowsEachServersRaftStateForARaftSwarmOnly() throws Exception {
        try (var manager = raftOnly()) {
            var servers = manager.startRaft("consensus", 3);
            manager.start("plain", 1);
            leaderAmong(manager, servers);
            var root = Json.read(Json.write(SwarmSnapshot.take(manager)));
            var byName = new LinkedHashMap<String, com.fasterxml.jackson.databind.JsonNode>();
            root.get("swarms").forEach(swarm -> byName.put(swarm.get("name").asText(), swarm));
            assertTrue(byName.get("consensus").get("raft").asBoolean());
            assertFalse(byName.get("plain").path("raft").asBoolean());
            var raftServer = byName.get("consensus").get("servers").get(0);
            assertEquals("running", raftServer.get("raft").get("state").asText());
            assertTrue(byName.get("plain").get("servers").get(0).get("raft").isNull(), "It is not asked: an ordinary server would show one more call for no reason");
        }
    }

    @Test void aSwarmOfTheLoadedPluginButNotStartedAsRaftStaysIdle() throws Exception {
        try (var manager = raftOnly()) {
            var plain = manager.start("plain", 1).getFirst();
            assertFalse(manager.isRaft("plain"));
            assertEquals(Map.of("state", "idle"), status(manager, plain.serverId()));
        }
    }

    @Test void aRaftSwarmIsLimitedInSizeAndItsVotersAreFixedForNow() throws Exception {
        try (var manager = raftOnly()) {
            assertThrows(IllegalArgumentException.class, () -> manager.startRaft("huge", SwarmManager.MAX_RAFT_SERVERS + 1));
            assertTrue(manager.list().isEmpty());
            var servers = manager.startRaft("fixed", 2);
            assertThrows(IllegalStateException.class, () -> manager.addServers("fixed", 1));
            assertThrows(IllegalStateException.class, () -> manager.removeServer(servers.getFirst().serverId()));
            assertEquals(2, manager.list().get("fixed").size());
        }
    }

    @Test void withoutThePluginLoadedARaftSwarmIsRefusedAndLeavesNothingBehind() throws Exception {
        try (var manager = new SwarmManager()) {
            var refused = assertThrows(IOException.class, () -> manager.startRaft("nope", 1));
            assertTrue(refused.getMessage().contains("Raft plugin"), refused.getMessage());
            assertTrue(manager.list().isEmpty());
            assertFalse(manager.isRaft("nope"));
        }
    }

    @Test void stoppingALeaderElectsAnotherAndItComesBackAsAFollowerWithItsLog() throws Exception {
        try (var manager = raftOnly()) {
            var servers = manager.startRaft("consensus", 3);
            var first = leaderAmong(manager, servers);
            long index = ((Number) propose(manager, first, "kept").get("index")).longValue();
            manager.stopServer(first);
            var others = servers.stream().filter(server -> !server.serverId().equals(first)).toList();
            var second = leaderAmong(manager, others);
            assertEquals("COMMITTED", propose(manager, second, "while it was away").get("status"));
            manager.restartServer(first);
            await("it follows, and has caught up", () -> {
                var status = status(manager, first);
                return second.equals(status.get("leader")) && "FOLLOWER".equals(status.get("role"))
                        && status.get("commitIndex") instanceof Number commit && commit.longValue() >= index + 1;
            });
        }
    }

    @Test void stoppingEveryServerAndRestartingThemLosesNothingCommitted() throws Exception {
        try (var manager = raftOnly()) {
            var servers = manager.startRaft("consensus", 3);
            var leader = leaderAmong(manager, servers);
            long index = ((Number) propose(manager, leader, "survives").get("index")).longValue();
            for (var server : servers) { manager.stopServer(server.serverId()); }
            for (var server : servers) { manager.restartServer(server.serverId()); }
            var next = leaderAmong(manager, servers);
            await("the new leader has committed past it", () -> status(manager, next).get("commitIndex") instanceof Number commit && commit.longValue() > index);
            var page = raft(manager, next, Map.of("operation", "log", "from", index, "limit", 1));
            assertTrue(page.get("entries").toString().contains("survives"), page.toString());
        }
    }

    @Test void aPartitionLeavesOnlyTheLargerSideAbleToCommitAndHealingBringsThemTogether() throws Exception {
        try (var manager = raftOnly()) {
            var servers = manager.startRaft("consensus", 5);
            var leader = leaderAmong(manager, servers);
            var ids = servers.stream().map(ServerEndpoint::serverId).toList();
            var small = new java.util.ArrayList<>(List.of(leader));
            small.add(ids.stream().filter(id -> !id.equals(leader)).findFirst().orElseThrow());
            var large = ids.stream().filter(id -> !small.contains(id)).toList();
            manager.network().partition(List.of(small, large));
            var cutOff = propose(manager, leader, "the small side cannot commit this");
            assertEquals("OUTCOME_UNKNOWN", cutOff.get("status"), cutOff.toString());
            var next = leaderAmong(manager, servers.stream().filter(server -> large.contains(server.serverId())).toList());
            var committed = propose(manager, next, "the large side can");
            assertEquals("COMMITTED", committed.get("status"), committed.toString());
            manager.network().heal();
            long index = ((Number) committed.get("index")).longValue();
            await("the small side has caught up", () -> small.stream().allMatch(id -> status(manager, id).get("commitIndex") instanceof Number commit && commit.longValue() >= index));
            for (var id : small) {
                assertFalse(raft(manager, id, Map.of("operation", "log", "from", 1, "limit", 100)).get("entries").toString().contains("cannot commit"),
                        "What the small side accepted must be gone once it has rejoined");
            }
        }
    }

    @Test void aFrozenLeaderIsReplacedAndStepsDownWhenThawed() throws Exception {
        try (var manager = raftOnly()) {
            var servers = manager.startRaft("consensus", 3);
            var old = leaderAmong(manager, servers);
            manager.freezeServer(old);
            var next = leaderAmong(manager, servers.stream().filter(server -> !server.serverId().equals(old)).toList());
            assertEquals("COMMITTED", propose(manager, next, "while frozen").get("status"));
            manager.thawServer(old);
            await("it follows the new leader", () -> {
                var status = status(manager, old);
                return "FOLLOWER".equals(status.get("role")) && next.equals(status.get("leader"));
            });
        }
    }

    @Test void kvOnARaftSwarmStaysLocalEvenWhenOtherSwarmsReplicate() throws Exception {
        try (var manager = new SwarmManager(List.of(GOSSIP, KV, RAFT), GossipSettings.fast(), true)) {
            var raftServers = manager.startRaft("consensus", 2);
            var ordinary = manager.start("replicating", 2);
            var request = "{\"instruction\":\"kv\",\"parameters\":{\"operation\":\"status\"}}";
            @SuppressWarnings("unchecked") var local = (Map<String, Object>) manager.command(raftServers.getFirst().serverId(), request).get("result");
            assertEquals("off", local.get("replication"), "Raft and kv's own replication at once would be silently wrong");
            @SuppressWarnings("unchecked") var replicating = (Map<String, Object>) manager.command(ordinary.getFirst().serverId(), request).get("result");
            assertEquals("on", replicating.get("replication"));
        }
    }

    @Test void stoppingASwarmDeletesWhatItKeptAndTheNameCanBeUsedAgainFromScratch() throws Exception {
        try (var manager = raftOnly()) {
            var servers = manager.startRaft("consensus", 1);
            var id = servers.getFirst().serverId();
            var leader = leaderAmong(manager, servers);
            propose(manager, leader, "from the first life");
            var kept = manager.raftFile().dataDirectory().resolve(id);
            assertTrue(Files.exists(kept.resolve("raft.log")));
            manager.stop("consensus");
            assertFalse(Files.exists(kept));
            assertFalse(manager.isRaft("consensus"));
            var again = manager.startRaft("consensus", 1);
            leaderAmong(manager, again);
            assertFalse(raft(manager, id, Map.of("operation", "log", "from", 1, "limit", 100)).get("entries").toString().contains("first life"));
        }
    }

    @Test void closingTheHarnessDeletesItsDataAndConfig() throws Exception {
        Path data;
        try (var manager = raftOnly()) {
            manager.startRaft("consensus", 1);
            data = manager.raftFile().dataDirectory();
            assertTrue(Files.isDirectory(data));
            assertNotNull(System.getProperty(RaftConfigFile.PROPERTY));
        }
        assertFalse(Files.exists(data));
        assertNull(System.getProperty(RaftConfigFile.PROPERTY));
    }

    @Test void thePageAndTheConsoleCanAskForARaftSwarm() throws Exception {
        try (var manager = raftOnly(); var ui = new UiServer(manager, 0)) {
            var http = HttpClient.newHttpClient();
            var response = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + ui.port() + "/api/swarms"))
                    .header("Origin", "http://localhost:" + ui.port()).header(UiServer.ACTION_HEADER, "1")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"viapage\",\"servers\":1,\"mode\":\"raft\"}")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), response.body());
            assertTrue(manager.isRaft("viapage"));
            var bad = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + ui.port() + "/api/swarms"))
                    .header("Origin", "http://localhost:" + ui.port()).header(UiServer.ACTION_HEADER, "1")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"other\",\"servers\":1,\"mode\":\"paxos\"}")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(400, bad.statusCode(), bad.body());
            assertFalse(manager.list().containsKey("other"));
        }
        try (var manager = raftOnly()) {
            var output = new java.io.StringWriter();
            SwarmMain.runConsole(manager, new java.io.BufferedReader(new java.io.StringReader("start viaconsole 1 raft\nstart plainconsole 1\nstart bad 1 paxos\nquit\n")), new java.io.PrintWriter(output, true));
            assertTrue(manager.isRaft("viaconsole"));
            assertFalse(manager.isRaft("plainconsole"));
            assertTrue(output.toString().contains("name=viaconsole"), output.toString());
            assertTrue(output.toString().contains("name=plainconsole"), output.toString());
            assertFalse(output.toString().contains("name=bad"), output.toString());
        }
    }
}
