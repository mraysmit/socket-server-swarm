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

    @Test void theSnapshotSumsUpARaftSwarmAndNoOtherForThePage() throws Exception {
        try (var manager = raftOnly()) {
            var servers = manager.startRaft("consensus", 3);
            manager.start("plain", 1);
            var leader = leaderAmong(manager, servers);
            var root = Json.read(Json.write(SwarmSnapshot.take(manager)));
            var byName = new LinkedHashMap<String, com.fasterxml.jackson.databind.JsonNode>();
            root.get("swarms").forEach(swarm -> byName.put(swarm.get("name").asText(), swarm));
            var summary = byName.get("consensus").get("raftSummary");
            assertEquals(leader, summary.get("leader").asText());
            assertEquals(3, summary.get("servers").asInt());
            assertEquals(3, summary.get("agree").asInt());
            assertFalse(byName.get("plain").has("raftSummary"));
        }
    }

    @Test void aLeaderCutOffOnTheSmallerSideIsSummedUpAsStaleOnceTheOtherSideElects() throws Exception {
        try (var manager = raftOnly()) {
            var servers = manager.startRaft("consensus", 3);
            var old = leaderAmong(manager, servers);
            var others = servers.stream().map(ServerEndpoint::serverId).filter(id -> !id.equals(old)).toList();
            manager.network().partition(List.of(List.of(old), others));
            await("the summary calls the old leader stale", () -> {
                try {
                    var summary = Json.read(Json.write(SwarmSnapshot.take(manager))).get("swarms").get(0).get("raftSummary");
                    return others.contains(summary.get("leader").asText()) && summary.get("staleLeaders").toString().contains(old);
                } catch (IOException notYet) { return false; }
            });
            manager.network().heal();
            await("it is a follower again, and nothing is stale", () -> {
                try {
                    var summary = Json.read(Json.write(SwarmSnapshot.take(manager))).get("swarms").get(0).get("raftSummary");
                    return summary.get("staleLeaders").isEmpty() && summary.get("agree").asInt() == 3;
                } catch (IOException notYet) { return false; }
            });
        }
    }

    @Test void thePageCanReadAPageOfOneServersLogWithoutShowingAsAClientsCall() throws Exception {
        var recorder = new SocketActivity();
        try (var ignored = recorder.install(); var manager = raftOnly();
             var calls = new CallLog(manager.network()::serverAt).install(); var ui = new UiServer(manager, 0, recorder, calls)) {
            recorder.reportTo(calls);
            manager.connections().reportSending(calls::harnessRequest);
            var servers = manager.startRaft("consensus", 1);
            var id = servers.getFirst().serverId();
            var leader = leaderAmong(manager, servers);
            propose(manager, leader, "first");
            propose(manager, leader, "second");
            var http = HttpClient.newHttpClient();
            var base = "http://localhost:" + ui.port() + "/api/servers/" + id + "/raft-log";
            Thread.sleep(200); // A server logs a reply only after sending it, so its line may still be on its way.
            long before = calls.view(0, 1000).calls().size();
            var page = http.send(HttpRequest.newBuilder(URI.create(base + "?from=2&limit=1")).GET().build(), HttpResponse.BodyHandlers.ofString());
            Thread.sleep(200);
            assertEquals(200, page.statusCode(), page.body());
            var entries = Json.read(page.body()).get("entries");
            assertEquals(1, entries.size());
            assertEquals(2, entries.get(0).get("index").asInt());
            assertTrue(entries.get(0).get("command").asText().contains("first"), page.body());
            assertEquals(before, calls.view(0, 1000).calls().size(), "Looking at the log is not a client's call");
            assertEquals(3, Json.read(http.send(HttpRequest.newBuilder(URI.create(base)).GET().build(), HttpResponse.BodyHandlers.ofString()).body()).get("entries").size(),
                    "From the start, when not told otherwise");
            assertEquals(400, http.send(HttpRequest.newBuilder(URI.create(base + "?limit=100000")).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode(), "The plugin's limit stands");
        }
    }

    @Test void aLogPageIsRefusedPlainlyForAServerThatCannotGiveOne() throws Exception {
        try (var manager = raftOnly(); var ui = new UiServer(manager, 0)) {
            var plain = manager.start("plain", 1).getFirst().serverId();
            var raftServer = manager.startRaft("consensus", 1).getFirst().serverId();
            var http = HttpClient.newHttpClient();
            var base = "http://localhost:" + ui.port() + "/api/servers/";
            assertEquals(404, http.send(HttpRequest.newBuilder(URI.create(base + "nobody-Server-001/raft-log")).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(409, http.send(HttpRequest.newBuilder(URI.create(base + plain + "/raft-log")).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            manager.freezeServer(raftServer);
            var frozen = http.send(HttpRequest.newBuilder(URI.create(base + raftServer + "/raft-log")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(409, frozen.statusCode(), "Asking would only wait out a timeout");
            assertTrue(frozen.body().contains("frozen"), frozen.body());
            manager.thawServer(raftServer);
            manager.stopServer(raftServer);
            assertEquals(502, http.send(HttpRequest.newBuilder(URI.create(base + raftServer + "/raft-log")).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }

    @Test void aSwarmOfTheLoadedPluginButNotStartedAsRaftStaysIdle() throws Exception {
        try (var manager = raftOnly()) {
            var plain = manager.start("plain", 1).getFirst();
            assertFalse(manager.isRaft("plain"));
            assertEquals(Map.of("state", "idle"), status(manager, plain.serverId()));
        }
    }

    // Phase 7 replaced aRaftSwarmIsLimitedInSizeAndItsVotersAreFixedForNow: the voters are no longer fixed, and the size limit stays.
    @Test void aRaftSwarmIsLimitedInSizeWhetherStartedOrAddedTo() throws Exception {
        try (var manager = raftOnly()) {
            assertThrows(IllegalArgumentException.class, () -> manager.startRaft("huge", SwarmManager.MAX_RAFT_SERVERS + 1));
            assertTrue(manager.list().isEmpty());
            manager.startRaft("full", SwarmManager.MAX_RAFT_SERVERS - 1);
            assertThrows(IllegalArgumentException.class, () -> manager.addServers("full", 2));
            assertEquals(SwarmManager.MAX_RAFT_SERVERS - 1, manager.list().get("full").size());
        }
    }

    // ---- Changing the voters (Phase 7) ----------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static List<String> votersOf(SwarmManager manager, String serverId) {
        return status(manager, serverId).get("voters") instanceof List<?> voters ? (List<String>) voters : List.of();
    }

    private static List<String> ids(List<ServerEndpoint> servers) { return servers.stream().map(ServerEndpoint::serverId).toList(); }

    @Test void aServerAddedToARaftSwarmCatchesUpThenVotesEverywhere() throws Exception {
        try (var manager = raftOnly()) {
            var servers = manager.startRaft("growing", 3);
            var leader = leaderAmong(manager, servers);
            long before = ((Number) propose(manager, leader, "before").get("index")).longValue();
            var added = manager.addServers("growing", 1);
            assertEquals(List.of("growing-Server-004"), ids(added));
            var all = manager.list().get("growing");
            assertEquals(4, all.size());
            await("every server has four voters", () -> all.stream().allMatch(server -> votersOf(manager, server.serverId()).equals(ids(all))));
            var page = raft(manager, "growing-Server-004", Map.of("operation", "log", "from", before, "limit", 1));
            assertTrue(page.get("entries").toString().contains("before"), "It caught up before it voted: " + page);
            var now = leaderAmong(manager, all);
            long after = ((Number) propose(manager, now, "after").get("index")).longValue();
            await("the new server committed what came after", () -> status(manager, "growing-Server-004").get("commitIndex") instanceof Number commit && commit.longValue() >= after);
        }
    }

    @Test void aServerAddedKeepsItsVoteThroughARestart() throws Exception {
        try (var manager = raftOnly()) {
            var servers = manager.startRaft("kept", 3);
            leaderAmong(manager, servers);
            manager.addServers("kept", 1);
            manager.stopServer("kept-Server-004");
            manager.restartServer("kept-Server-004");
            await("it is back with its vote", () -> votersOf(manager, "kept-Server-004").contains("kept-Server-004"));
        }
    }

    @Test void aFollowerRemovedLosesItsVoteAndIsStopped() throws Exception {
        try (var manager = raftOnly()) {
            var servers = manager.startRaft("shrinking", 3);
            var leader = leaderAmong(manager, servers);
            var follower = servers.stream().map(ServerEndpoint::serverId).filter(id -> !id.equals(leader)).findFirst().orElseThrow();
            manager.removeServer(follower);
            var rest = manager.list().get("shrinking");
            assertEquals(2, rest.size());
            assertFalse(ids(rest).contains(follower));
            await("the rest have each other as voters", () -> rest.stream().allMatch(server -> votersOf(manager, server.serverId()).equals(ids(rest))));
            assertEquals("COMMITTED", propose(manager, leaderAmong(manager, rest), "after").get("status"));
        }
    }

    @Test void theLeaderRemovedHandsOverToAnother() throws Exception {
        try (var manager = raftOnly()) {
            var servers = manager.startRaft("handover", 3);
            var leader = leaderAmong(manager, servers);
            manager.removeServer(leader);
            var rest = manager.list().get("handover");
            var next = leaderAmong(manager, rest);
            assertNotEquals(leader, next);
            assertEquals(ids(rest), votersOf(manager, next));
            assertEquals("COMMITTED", propose(manager, next, "after").get("status"));
        }
    }

    @Test void theLastServerOfARaftSwarmIsNotRemoved() throws Exception {
        try (var manager = raftOnly()) {
            var only = manager.startRaft("alone", 1).getFirst();
            leaderAmong(manager, List.of(only));
            var refused = assertThrows(IllegalStateException.class, () -> manager.removeServer(only.serverId()));
            assertTrue(refused.getMessage().contains("last voter"), refused.getMessage());
            assertEquals(1, manager.list().get("alone").size());
            assertEquals("LEADER", status(manager, only.serverId()).get("role"));
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

    // ---- Applying to plugins ------------------------------------------------------------------------------------------

    private static Map<String, Object> kvPut(String key, String value) {
        return Map.of("instruction", "kv", "parameters", Map.of("operation", "put", "key", key, "value", value));
    }

    private static Map<String, Object> kvGet(String key) {
        return Map.of("instruction", "kv", "parameters", Map.of("operation", "get", "key", key));
    }

    private static Map<String, Object> proposeCommand(SwarmManager manager, String serverId, Map<String, Object> command) throws IOException {
        return raft(manager, serverId, Map.of("operation", "propose", "command", command));
    }

    /** The answer once the server leading has committed the command, trying again as a client must. */
    private static Map<String, Object> commit(SwarmManager manager, List<ServerEndpoint> servers, Map<String, Object> command) throws Exception {
        var answer = new LinkedHashMap<String, Object>();
        await("it commits", () -> {
            try {
                answer.clear();
                answer.putAll(proposeCommand(manager, leaderAmong(manager, servers), command));
                return "COMMITTED".equals(answer.get("status"));
            } catch (Exception notYet) { return false; }
        });
        return answer;
    }

    private static void awaitApplied(SwarmManager manager, ServerEndpoint server, long index) throws Exception {
        await(server.serverId() + " has applied up to " + index, () -> status(manager, server.serverId()).get("appliedIndex") instanceof Number applied && applied.longValue() >= index);
    }

    private static String kvValue(SwarmManager manager, ServerEndpoint server, String key) throws IOException {
        var request = "{\"instruction\":\"kv\",\"parameters\":{\"operation\":\"get\",\"key\":\"" + key + "\"}}";
        var outcome = manager.command(server.serverId(), request);
        assertEquals(Boolean.TRUE, outcome.get("ok"), outcome.toString());
        return String.valueOf(((Map<?, ?>) outcome.get("result")).get("value"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readValue(SwarmManager manager, List<ServerEndpoint> servers, String key) throws Exception {
        var answer = new LinkedHashMap<String, Object>();
        await("it can be read", () -> {
            try {
                answer.clear();
                answer.putAll(raft(manager, leaderAmong(manager, servers), Map.of("operation", "read", "command", kvGet(key))));
                return "READ".equals(answer.get("status"));
            } catch (Exception notYet) { return false; }
        });
        return (Map<String, Object>) answer.get("result");
    }

    // ---- Snapshots (Phase 8) ------------------------------------------------------------------------------------------

    private static long snapshotIndex(SwarmManager manager, String serverId) {
        return status(manager, serverId).get("snapshotIndex") instanceof Number index ? index.longValue() : 0;
    }

    @Test void aServerAddedAfterTheLogWasCompactedIsCaughtUpByASnapshotOfTheRealKv() throws Exception {
        try (var manager = new SwarmManager(List.of(KV, RAFT))) {
            var servers = manager.startRaft("compacted", 3);
            for (int i = 0; i < 40; i++) { commit(manager, servers, kvPut("key-" + (i % 7), "value-" + i)); }
            var leader = leaderAmong(manager, servers);
            await("the leader compacted its log", () -> snapshotIndex(manager, leader) > 0);
            var added = manager.addServers("compacted", 1).getFirst();
            assertTrue(snapshotIndex(manager, added.serverId()) > 0, "It needed what was compacted, so it was sent a snapshot");
            long last = ((Number) commit(manager, manager.list().get("compacted"), kvPut("key-0", "after")).get("index")).longValue();
            awaitApplied(manager, added, last);
            var leaderNow = leaderAmong(manager, servers);
            var leading = manager.list().get("compacted").stream().filter(server -> server.serverId().equals(leaderNow)).findFirst().orElseThrow();
            for (int key = 0; key < 7; key++) {
                assertEquals(kvValue(manager, leading, "key-" + key), kvValue(manager, added, "key-" + key), "key-" + key);
            }
        }
    }

    @Test void theRealKvBehindRaftHoldsTheSameEverywhereAndCanBeReadThroughTheLeader() throws Exception {
        try (var manager = new SwarmManager(List.of(KV, RAFT))) {
            var servers = manager.startRaft("distributed", 3);
            var answer = commit(manager, servers, kvPut("colour", "blue"));
            assertEquals(true, answer.get("applied"), answer.toString());
            for (var server : servers) {
                awaitApplied(manager, server, ((Number) answer.get("index")).longValue());
                assertEquals("blue", kvValue(manager, server, "colour"));
            }
            assertEquals("blue", readValue(manager, servers, "colour").get("value"));
        }
    }

    @Test void theRealKvComesBackAfterEveryServerRestartsThoughItKeepsNothing() throws Exception {
        try (var manager = new SwarmManager(List.of(KV, RAFT))) {
            var servers = manager.startRaft("distributed", 3);
            long index = ((Number) commit(manager, servers, kvPut("colour", "blue")).get("index")).longValue();
            for (var server : servers) { manager.stopServer(server.serverId()); }
            for (var server : servers) { manager.restartServer(server.serverId()); }
            for (var server : servers) {
                awaitApplied(manager, server, index);
                assertEquals("blue", kvValue(manager, server, "colour"));
            }
        }
    }

    @Test void raftWorksInAllFourCombinationsOfTheOtherPlugins() throws Exception {
        record Combination(String name, List<Path> plugins, GossipSettings gossip, boolean hasKv) { }
        var combinations = List.of(
                new Combination("alone", List.of(RAFT), null, false),
                new Combination("with gossip", List.of(GOSSIP, RAFT), GossipSettings.fast(), false),
                new Combination("with kv", List.of(KV, RAFT), null, true),
                new Combination("with both", List.of(GOSSIP, KV, RAFT), GossipSettings.fast(), true));
        for (var combination : combinations) {
            try (var manager = new SwarmManager(combination.plugins(), combination.gossip())) {
                var servers = manager.startRaft("combination", 3);
                var answer = commit(manager, servers, kvPut("colour", "blue"));
                assertEquals(combination.hasKv(), answer.get("applied"), combination.name() + ": " + answer);
                if (combination.hasKv()) { assertEquals("blue", readValue(manager, servers, "colour").get("value"), combination.name()); }
                else { await(combination.name() + " waits for kv", () -> status(manager, servers.getFirst().serverId()).containsKey("waitingFor")); }
            }
        }
    }

    @Test void aPluginHeldBackCommitsNowAndCatchesUpWhenEachServerRestartsWithIt() throws Exception {
        try (var manager = new SwarmManager(List.of(KV, RAFT))) {
            var servers = manager.startRaft("late", 3, "kv");
            assertEquals(List.of("echo", "raft"), manager.instructions(servers.getFirst().serverId()).stream().sorted().toList());
            var answer = commit(manager, servers, kvPut("colour", "blue"));
            assertEquals(false, answer.get("applied"), answer.toString());
            long index = ((Number) answer.get("index")).longValue();
            manager.restartServer(servers.getFirst().serverId());
            assertEquals(List.of("echo", "raft"), manager.instructions(servers.getFirst().serverId()).stream().sorted().toList(), "An ordinary restart keeps it held back");
            for (var server : servers) {
                manager.restartServerWithAllPlugins(server.serverId());
                assertEquals(List.of("echo", "kv", "raft"), manager.instructions(server.serverId()).stream().sorted().toList());
                awaitApplied(manager, server, index);
                assertEquals("blue", kvValue(manager, server, "colour"));
            }
        }
    }

    @Test void theSnapshotSeesAPluginAServerGainedByRestarting() throws Exception {
        try (var manager = new SwarmManager(List.of(KV, RAFT))) {
            var server = manager.startRaft("late", 1, "kv").getFirst();
            leaderAmong(manager, List.of(server));
            var before = Json.read(Json.write(SwarmSnapshot.take(manager))).get("swarms").get(0).get("servers").get(0);
            assertTrue(before.get("kv").isNull());
            manager.restartServerWithAllPlugins(server.serverId());
            var after = Json.read(Json.write(SwarmSnapshot.take(manager))).get("swarms").get(0).get("servers").get(0);
            assertFalse(after.get("kv").isNull(), "It asked once and got no answer, but that was before the restart");
        }
    }

    @Test void holdingBackAPluginNoFolderHoldsIsRefusedAndLeavesNothingBehind() throws Exception {
        try (var manager = raftOnly()) {
            assertThrows(IllegalArgumentException.class, () -> manager.startRaft("late", 1, "nonsense"));
            assertTrue(manager.list().isEmpty());
            assertFalse(manager.isRaft("late"));
        }
    }

    @Test void stoppingASwarmForgetsWhatWasHeldBackSoTheNameStartsAfresh() throws Exception {
        try (var manager = new SwarmManager(List.of(KV, RAFT))) {
            var server = manager.startRaft("late", 1, "kv").getFirst();
            manager.stop("late");
            assertEquals(server.serverId(), manager.startRaft("late", 1).getFirst().serverId());
            assertTrue(manager.instructions(server.serverId()).contains("kv"));
        }
    }

    @Test void thePageCanStartASwarmWithoutAPluginAndActivateItLater() throws Exception {
        try (var manager = new SwarmManager(List.of(KV, RAFT)); var ui = new UiServer(manager, 0)) {
            var http = HttpClient.newHttpClient();
            var base = "http://localhost:" + ui.port();
            var started = http.send(HttpRequest.newBuilder(URI.create(base + "/api/swarms"))
                    .header("Origin", base).header(UiServer.ACTION_HEADER, "1")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"late\",\"servers\":1,\"mode\":\"raft\",\"without\":\"kv\"}")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, started.statusCode(), started.body());
            var id = manager.list().get("late").getFirst().serverId();
            assertFalse(manager.instructions(id).contains("kv"));
            var restarted = http.send(HttpRequest.newBuilder(URI.create(base + "/api/servers/" + id + "/restart-with-all-plugins"))
                    .header("Origin", base).header(UiServer.ACTION_HEADER, "1")
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, restarted.statusCode(), restarted.body());
            assertTrue(manager.instructions(id).contains("kv"));
            var refused = http.send(HttpRequest.newBuilder(URI.create(base + "/api/swarms"))
                    .header("Origin", base).header(UiServer.ACTION_HEADER, "1")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"plain\",\"servers\":1,\"without\":\"kv\"}")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(400, refused.statusCode(), refused.body());
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
