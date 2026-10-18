package dev.mars.swarm;

import dev.mars.server.ServerInstance;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs real servers with the gossip plugin. The config tests always run; the swarm tests need the plugin built in the
 * sibling socket-server-threadpool project and are skipped when it is missing.
 */
@Timeout(90)
class SwarmGossipTest {
    private static final Path PLUGIN = Path.of("..", "socket-server-threadpool", "plugins", "gossip-plugin", "target");
    private static final long DEADLINE_MILLIS = 30_000;

    @Test
    void generatedConfigGivesEachServerItsSwarmAndSeedsPointAtTheFirstServer() throws Exception {
        var seen = new ConcurrentHashMap<String, Properties>();
        var opened = new ConcurrentLinkedQueue<ServerInstance>();
        String configPath;
        try (var manager = new SwarmManager(List.of(), GossipSettings.fast(), options -> {
            seen.put(options.serverId(), readConfig());
            var server = ServerInstance.start(options);
            opened.add(server);
            return server;
        })) {
            manager.start("alpha", 3);
            manager.start("beta", 2);
            configPath = System.getProperty("gossip.config");
            assertNotNull(configPath);
            assertTrue(Files.exists(Path.of(configPath)));
            var first = opened.stream().filter(server -> server.getServerId().equals("alpha-Server-001")).findFirst().orElseThrow();

            // The first server starts alone, so it never sees a seed; the others are told its real port.
            assertNull(seen.get("alpha-Server-001").getProperty("server.alpha-Server-001.seeds"));
            String seed = "alpha-Server-001@127.0.0.1:" + first.getPort();
            assertEquals(seed, seen.get("alpha-Server-002").getProperty("server.alpha-Server-002.seeds"));
            assertEquals(seed, seen.get("alpha-Server-003").getProperty("server.alpha-Server-003.seeds"));
            for (var id : List.of("alpha-Server-001", "alpha-Server-002", "alpha-Server-003")) {
                assertEquals("alpha", seen.get(id).getProperty("server." + id + ".swarm.id"));
            }
            assertEquals("beta", seen.get("beta-Server-002").getProperty("server.beta-Server-002.swarm.id"));
            assertEquals("127.0.0.1", seen.get("beta-Server-001").getProperty("advertise.host"));
            assertEquals("200", seen.get("beta-Server-001").getProperty("round.millis"));

            manager.stop("alpha");
            var afterStop = readConfig();
            assertNull(afterStop.getProperty("server.alpha-Server-001.swarm.id"));
            assertEquals("beta", afterStop.getProperty("server.beta-Server-001.swarm.id"));
        }
        assertNull(System.getProperty("gossip.config"), "The property is cleared on close");
        assertFalse(Files.exists(Path.of(configPath)), "The generated file is deleted on close");
    }

    @Test
    void failedStartupForgetsTheSwarmsConfigAndNoGossipMeansNoProperty() throws Exception {
        try (var manager = new SwarmManager(List.of(), GossipSettings.defaults(), options -> {
            if (options.serverId().equals("broken-Server-001")) { throw new IOException("Deliberate startup failure"); }
            return ServerInstance.start(options);
        })) {
            assertThrows(IOException.class, () -> manager.start("broken", 2));
            assertNull(readConfig().getProperty("server.broken-Server-001.swarm.id"));
            assertTrue(manager.list().isEmpty());
        }
        try (var manager = new SwarmManager()) {
            manager.start("plain", 1);
            assertNull(System.getProperty("gossip.config"));
            assertFalse(manager.gossipEnabled());
        }
    }

    @Test
    void settingsRejectHarnessOwnedAndBlankValues() {
        assertThrows(IllegalArgumentException.class, () -> new GossipSettings("", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new GossipSettings("127.0.0.1", Map.of("seeds", "x@y:1")));
        assertThrows(IllegalArgumentException.class, () -> new GossipSettings("127.0.0.1", Map.of("swarm.id", "x")));
        assertThrows(IllegalArgumentException.class, () -> new GossipSettings("127.0.0.1", Map.of("server.a.swarm.id", "x")));
        assertThrows(IllegalArgumentException.class, () -> new GossipSettings("127.0.0.1", Map.of("advertise.host", "x")));
        assertEquals("500", GossipSettings.fast().options().get("heartbeat.millis"));
    }

    @Test
    void parsesGossipOptions() {
        assertNull(SwarmMain.parseOptions().gossip());
        assertEquals(GossipSettings.defaults(), SwarmMain.parseOptions("--plugins-dir", "p", "--gossip").gossip());
        assertEquals(GossipSettings.fast(), SwarmMain.parseOptions("--gossip-fast", "--plugins-dir", "p").gossip());
        assertThrows(IllegalArgumentException.class, () -> SwarmMain.parseOptions("--gossip"));
        assertThrows(IllegalArgumentException.class, () -> SwarmMain.parseOptions("--plugins-dir", "p", "--gossip", "--gossip-fast"));
    }

    @Test
    void serversConvergeAndReportTheirViewsThroughTheSnapshot() throws Exception {
        requirePlugin();
        try (var manager = new SwarmManager(List.of(PLUGIN), GossipSettings.fast())) {
            var endpoints = manager.start("conv", 4);
            awaitAllSee(manager, endpoints, "ALIVE", 4);

            var snapshot = SwarmSnapshot.take(manager);
            @SuppressWarnings("unchecked") var swarms = (List<Map<String, Object>>) snapshot.get("swarms");
            @SuppressWarnings("unchecked") var servers = (List<Map<String, Object>>) swarms.getFirst().get("servers");
            assertEquals(4, servers.size());
            for (var server : servers) {
                assertEquals(Boolean.TRUE, server.get("alive"));
                @SuppressWarnings("unchecked") var gossip = (Map<String, Object>) server.get("gossip");
                assertNotNull(gossip, "Gossip data for " + server.get("serverId"));
                @SuppressWarnings("unchecked") var status = (Map<String, Object>) gossip.get("status");
                assertEquals("RUNNING", status.get("workerState"));
                assertEquals("conv", status.get("swarmId"));
                assertEquals(Boolean.FALSE, status.get("isolated"));
            }
        }
    }

    @Test
    void swarmsStaySeparateAndUseTheirOwnSwarmId() throws Exception {
        requirePlugin();
        try (var manager = new SwarmManager(List.of(PLUGIN), GossipSettings.fast())) {
            var alpha = manager.start("alpha", 3);
            var beta = manager.start("beta", 2);
            awaitAllSee(manager, alpha, "ALIVE", 3);
            awaitAllSee(manager, beta, "ALIVE", 2);
            for (var endpoint : alpha) { assertEquals("alpha", status(manager, endpoint).get("swarmId")); }
            for (var endpoint : beta) {
                assertEquals("beta", status(manager, endpoint).get("swarmId"));
                assertTrue(members(manager, endpoint).keySet().stream().allMatch(id -> id.startsWith("beta-")));
            }
        }
    }

    @Test
    void closedServerIsSuspectedByThePeersWhichStayHealthy() throws Exception {
        requirePlugin();
        var opened = new ConcurrentLinkedQueue<ServerInstance>();
        try (var manager = new SwarmManager(List.of(PLUGIN), GossipSettings.fast(), options -> {
            var server = ServerInstance.start(options); opened.add(server); return server;
        })) {
            var endpoints = manager.start("fail", 3);
            awaitAllSee(manager, endpoints, "ALIVE", 3);
            opened.stream().filter(server -> server.getServerId().equals("fail-Server-003")).findFirst().orElseThrow().close();
            var survivors = endpoints.subList(0, 2);
            await("survivors suspect fail-Server-003", () -> survivors.stream().allMatch(endpoint -> {
                String rating = rating(members(manager, endpoint), "fail-Server-003");
                return rating.equals("SUSPECT") || rating.equals("DEAD");
            }));
            // The two healthy servers still rate each other ALIVE.
            assertEquals("ALIVE", rating(members(manager, survivors.get(0)), "fail-Server-002"));
            assertEquals("ALIVE", rating(members(manager, survivors.get(1)), "fail-Server-001"));
        }
    }

    @Test
    void leavingServerIsMarkedLeftByThePeersQuickly() throws Exception {
        requirePlugin();
        try (var manager = new SwarmManager(List.of(PLUGIN), GossipSettings.fast())) {
            var endpoints = manager.start("leave", 3);
            awaitAllSee(manager, endpoints, "ALIVE", 3);
            var reply = manager.execute(endpoints.get(2), "gossip", Map.of("operation", "leave"));
            assertEquals("OK", reply.status(), reply.toString());
            var others = endpoints.subList(0, 2);
            await("peers record LEFT", () -> others.stream().allMatch(endpoint ->
                    rating(members(manager, endpoint), "leave-Server-003").equals("LEFT")));
            await("departure completes", () -> "COMPLETED".equals(departure(manager, endpoints.get(2))));
        }
    }

    @Test
    void restartedServerGetsANewBootIdAndRejoinsTheSwarm() throws Exception {
        requirePlugin();
        try (var manager = new SwarmManager(List.of(PLUGIN), GossipSettings.fast())) {
            var endpoints = manager.start("rst", 3);
            awaitAllSee(manager, endpoints, "ALIVE", 3);
            String oldBoot = bootId(manager, endpoints.get(2));

            manager.restartServer("rst-Server-003");
            await("restarted server reports a new boot ID and is not isolated", () ->
                    !oldBoot.equals(bootId(manager, endpoints.get(2)))
                            && Boolean.FALSE.equals(status(manager, endpoints.get(2)).get("isolated")));
            String newBoot = bootId(manager, endpoints.get(2));
            for (var peer : endpoints.subList(0, 2)) {
                await(peer.serverId() + " rates the new boot ID ALIVE", () -> memberRows(manager, peer).stream().anyMatch(row ->
                        row.get("serverId").equals("rst-Server-003") && newBoot.equals(row.get("bootId")) && "ALIVE".equals(row.get("assessment"))));
            }
            await("restarted server sees both peers ALIVE", () -> members(manager, endpoints.get(2)).values().stream().filter("ALIVE"::equals).count() >= 2);
        }
    }

    @Test
    void echoTrafficStillWorksWhileTheServersGossip() throws Exception {
        requirePlugin();
        try (var manager = new SwarmManager(List.of(PLUGIN), GossipSettings.fast())) {
            var endpoints = manager.start("busy", 3);
            var result = manager.test("busy", 300, 30, done -> { });
            assertTrue(result.successful(), result.toString() + result.failures());
            awaitAllSee(manager, endpoints, "ALIVE", 3);
            assertTrue(manager.status("busy").stream().allMatch(ServerStatus::alive));
        }
    }

    @Test
    void consoleStartsAGossipSwarmAndSendsAGracefulLeave() throws Exception {
        requirePlugin();
        var output = new java.io.StringWriter();
        String configPath;
        try (var manager = new SwarmManager(List.of(PLUGIN), GossipSettings.fast())) {
            SwarmMain.runConsole(manager, new java.io.BufferedReader(new java.io.StringReader("""
                    start con 3
                    send con {"instruction":"gossip","parameters":{"operation":"status"}}
                    server leave con-Server-003
                    send con {"instruction":"gossip","parameters":{"operation":"status"}}
                    quit
                    """)), new java.io.PrintWriter(output, true));
            configPath = System.getProperty("gossip.config");
        }
        String text = output.toString();
        assertFalse(text.contains("Error:"), text);
        assertTrue(text.contains("Gossip plugin configuration is generated"), text);
        var lines = text.lines().filter(line -> line.startsWith("Swarm reply") && line.contains("serverId=con-Server-003")).toList();
        assertEquals(2, lines.size(), text);
        assertTrue(lines.get(0).contains("\"workerState\":\"RUNNING\""), lines.get(0));
        assertTrue(lines.get(1).contains("\"workerState\":\"LEAVING\"") || lines.get(1).contains("\"workerState\":\"QUIESCED\""), lines.get(1));
        assertTrue(text.contains("Server left | serverId=con-Server-003"), text);
        assertNotNull(configPath);
        assertNull(System.getProperty("gossip.config"));
        assertFalse(Files.exists(Path.of(configPath)));
    }

    @Test
    void swarmSeededAtAnotherSwarmRejectsItAndKeepsItsOwnMembers() throws Exception {
        requirePlugin();
        var alphaPort = new java.util.concurrent.atomic.AtomicInteger();
        try (var manager = new SwarmManager(List.of(PLUGIN), GossipSettings.fast(), options -> {
            if (options.serverId().equals("beta-Server-002")) { addSeed("beta-Server-002", "alpha-Server-001@127.0.0.1:" + alphaPort.get()); }
            return ServerInstance.start(options);
        })) {
            var alpha = manager.start("alpha", 2);
            alphaPort.set(alpha.getFirst().port());
            var beta = manager.start("beta", 2);
            awaitAllSee(manager, alpha, "ALIVE", 2);
            awaitAllSee(manager, beta, "ALIVE", 2);
            await("alpha-Server-001 turns away an exchange from the other swarm", () -> {
                @SuppressWarnings("unchecked") var exchanges = (Map<String, Object>) status(manager, alpha.getFirst()).get("exchanges");
                return ((Number) exchanges.get("rejected")).longValue() > 0;
            });
            for (var endpoint : alpha) { assertTrue(members(manager, endpoint).keySet().stream().allMatch(id -> id.startsWith("alpha-"))); }
            for (var endpoint : beta) { assertTrue(members(manager, endpoint).keySet().stream().allMatch(id -> id.startsWith("beta-"))); }
        }
    }

    @Test
    @Timeout(120)
    void aSwarmLargerThanOneMembersPageIsReadWholeByFollowingTheCursor() throws Exception {
        requirePlugin();
        int size = 40;
        try (var manager = new SwarmManager(List.of(PLUGIN), GossipSettings.fast())) {
            var endpoints = manager.start("wide", size);
            var first = endpoints.getFirst();
            await("server 001 rates all " + size + " members ALIVE", () ->
                    ((Number) ((Map<?, ?>) status(manager, first).get("assessments")).get("ALIVE")).intValue() >= size);

            @SuppressWarnings("unchecked")
            var page = (Map<String, Object>) manager.execute(first, "gossip", Map.of("operation", "members", "limit", 32)).result();
            assertNotNull(page.get("nextCursor"), "One page of 32 cannot hold " + size + " members");

            var whole = SwarmSnapshot.fullMembers(manager, first);
            assertEquals(Boolean.TRUE, whole.get("complete"));
            assertTrue(((Number) whole.get("pages")).intValue() >= 2);
            @SuppressWarnings("unchecked")
            var rows = (List<Map<String, Object>>) whole.get("members");
            var ids = new java.util.HashSet<String>();
            rows.forEach(row -> ids.add((String) row.get("serverId")));
            assertEquals(size, ids.size(), "Every member appears exactly once across the pages");
            assertTrue(rows.stream().allMatch(row -> "ALIVE".equals(row.get("assessment"))));
        }
    }

    /** Adds an extra seed to the generated config, as it stands just before a server reads it. */
    private static void addSeed(String serverId, String seed) {
        try {
            var properties = readConfig();
            String key = "server." + serverId + ".seeds";
            properties.setProperty(key, properties.getProperty(key) + "," + seed);
            try (var out = Files.newBufferedWriter(Path.of(System.getProperty("gossip.config")), StandardCharsets.UTF_8)) { properties.store(out, null); }
        } catch (IOException e) { throw new IllegalStateException(e); }
    }

    @SuppressWarnings("unchecked")
    private static String bootId(SwarmManager manager, ServerEndpoint endpoint) {
        return (String) ((Map<String, Object>) status(manager, endpoint).get("self")).get("bootId");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> memberRows(SwarmManager manager, ServerEndpoint endpoint) {
        try {
            var reply = manager.execute(endpoint, "gossip", Map.of("operation", "members", "limit", 32));
            assertEquals("OK", reply.status(), reply.toString());
            return (List<Map<String, Object>>) ((Map<String, Object>) reply.result()).get("members");
        } catch (IOException e) { throw new IllegalStateException(e); }
    }

    /** Waits until every server rates {@code expected} members, itself included, with the given assessment. */
    private static void awaitAllSee(SwarmManager manager, List<ServerEndpoint> endpoints, String assessment, int expected) throws Exception {
        await("every server sees " + expected + " members " + assessment, () -> endpoints.stream().allMatch(endpoint -> {
            var view = members(manager, endpoint);
            long alive = view.values().stream().filter(assessment::equals).count();
            return alive + 1 >= expected && Boolean.FALSE.equals(status(manager, endpoint).get("isolated"));
        }));
    }

    private static void await(String what, BooleanSupplier condition) throws Exception {
        long until = System.currentTimeMillis() + DEADLINE_MILLIS;
        while (System.currentTimeMillis() < until) {
            try { if (condition.getAsBoolean()) { return; } } catch (RuntimeException retry) { /* not ready yet */ }
            Thread.sleep(200);
        }
        fail("Timed out waiting until " + what);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> status(SwarmManager manager, ServerEndpoint endpoint) {
        try {
            var reply = manager.execute(endpoint, "gossip", Map.of("operation", "status"));
            assertEquals("OK", reply.status(), reply.toString());
            return (Map<String, Object>) reply.result();
        } catch (IOException e) { throw new IllegalStateException(e); }
    }

    private static String departure(SwarmManager manager, ServerEndpoint endpoint) {
        @SuppressWarnings("unchecked") var departure = (Map<String, Object>) status(manager, endpoint).get("departure");
        return (String) departure.get("state");
    }

    /** serverId to assessment for the members a server lists; the server itself is not in this map. */
    @SuppressWarnings("unchecked")
    private static Map<String, String> members(SwarmManager manager, ServerEndpoint endpoint) {
        try {
            var reply = manager.execute(endpoint, "gossip", Map.of("operation", "members", "limit", 32));
            assertEquals("OK", reply.status(), reply.toString());
            var result = (Map<String, Object>) reply.result();
            var self = (Map<String, Object>) result.get("self");
            var view = new HashMap<String, String>();
            for (var row : (List<Map<String, Object>>) result.get("members")) {
                if (!row.get("serverId").equals(self.get("serverId"))) { view.put((String) row.get("serverId"), (String) row.get("assessment")); }
            }
            return view;
        } catch (IOException e) { throw new IllegalStateException(e); }
    }

    private static Properties readConfig() {
        try (var reader = Files.newBufferedReader(Path.of(System.getProperty("gossip.config")), StandardCharsets.UTF_8)) {
            var properties = new Properties();
            properties.load(reader);
            return properties;
        } catch (IOException e) { throw new IllegalStateException(e); }
    }

    private static void requirePlugin() throws IOException {
        boolean built = Files.isDirectory(PLUGIN);
        if (built) { try (var files = Files.list(PLUGIN)) { built = files.anyMatch(file -> file.getFileName().toString().endsWith(".jar")); } }
        Assumptions.assumeTrue(built, "Build the gossip plugin first: mvn -f ../socket-server-threadpool/plugins/gossip-plugin/pom.xml package");
    }

    /** A member the server does not list reads as "MISSING" instead of null. */
    private static String rating(Map<String, String> view, String id) { return view.getOrDefault(id, "MISSING"); }
}
