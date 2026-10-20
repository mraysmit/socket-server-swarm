package dev.mars.swarm;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/** What the harness does to switch on key-value replication: the option, the configuration file, and the manager wiring. */
class SwarmKvTest {
    @Test
    void kvReplicationIsOffByDefault() {
        assertFalse(SwarmMain.parseOptions().kvReplicate());
        assertFalse(SwarmMain.parseOptions("--plugins-dir", "p", "--gossip-fast").kvReplicate());
    }

    @Test
    void theKvReplicateOptionSwitchesItOnAlongsideGossip() {
        assertTrue(SwarmMain.parseOptions("--plugins-dir", "p", "--gossip-fast", "--kv-replicate").kvReplicate());
        assertTrue(SwarmMain.parseOptions("--kv-replicate", "--gossip", "--plugins-dir", "p").kvReplicate());
    }

    @Test
    void theGeneratedFileTurnsReplicationOnAndIsNamedByTheKvConfigProperty() throws Exception {
        try (var file = KvConfigFile.open()) {
            assertTrue(Files.exists(file.path()));
            assertEquals(file.path().toString(), System.getProperty("kv.config"));
            var settings = new Properties();
            try (var in = Files.newInputStream(file.path())) { settings.load(in); }
            assertEquals("on", settings.getProperty("replication"));
        }
    }

    @Test
    void closingRemovesTheFileAndTheProperty() throws Exception {
        var file = KvConfigFile.open();
        var path = file.path();
        file.close();
        assertFalse(Files.exists(path));
        assertNull(System.getProperty("kv.config"));
        file.close();
    }

    @Test
    void closingLeavesAPropertyThatNowPointsAtAnotherFileAlone() throws Exception {
        var file = KvConfigFile.open();
        System.setProperty("kv.config", "somewhere-else.properties");
        try {
            file.close();
            assertEquals("somewhere-else.properties", System.getProperty("kv.config"));
        } finally {
            System.clearProperty("kv.config");
        }
    }

    @Test
    void aManagerAskedForKvReplicationHasTheConfigFileWhileItRunsAndRemovesItOnClose() throws Exception {
        Path file;
        try (var manager = new SwarmManager(List.of(), GossipSettings.fast(), true)) {
            manager.start("kv", 1);
            assertNotNull(System.getProperty("kv.config"));
            file = Path.of(System.getProperty("kv.config"));
            assertTrue(Files.exists(file));
        }
        assertNull(System.getProperty("kv.config"));
        assertFalse(Files.exists(file));
    }

    @Test
    void withoutKvReplicationTheManagerLeavesTheKvConfigPropertyAlone() throws Exception {
        try (var manager = new SwarmManager(List.of(), GossipSettings.fast(), false)) {
            manager.start("plain", 1);
            assertNull(System.getProperty("kv.config"));
        }
        try (var manager = new SwarmManager(List.of(), GossipSettings.fast())) {
            manager.start("plain", 1);
            assertNull(System.getProperty("kv.config"));
        }
    }

    private static Map<String, Object> server(boolean alive, String replication, String root) {
        var server = new LinkedHashMap<String, Object>();
        server.put("alive", alive);
        if (replication == null) {
            server.put("kv", null);
        } else {
            var kv = new LinkedHashMap<String, Object>();
            kv.put("replication", replication);
            kv.put("root", root);
            server.put("kv", kv);
        }
        return server;
    }

    @Test
    void theSummaryCountsTheServersHoldingTheSameRootAsTheMajority() {
        var summary = SwarmSnapshot.kvSync(List.of(server(true, "on", "aaaa"), server(true, "on", "aaaa"), server(true, "on", "aaaa")));
        assertEquals(Map.of("replicating", 3, "inSync", 3, "root", "aaaa"), summary);
    }

    @Test
    void aServerWithAnotherRootIsLeftOutOfTheInSyncCount() {
        var summary = SwarmSnapshot.kvSync(List.of(server(true, "on", "aaaa"), server(true, "on", "bbbb"), server(true, "on", "aaaa"), server(true, "on", "cccc")));
        assertEquals(Map.of("replicating", 4, "inSync", 2, "root", "aaaa"), summary);
    }

    @Test
    void aTieForTheMajorityIsBrokenTheSameWayEveryTime() {
        var summary = SwarmSnapshot.kvSync(List.of(server(true, "on", "bbbb"), server(true, "on", "aaaa")));
        assertEquals(Map.of("replicating", 2, "inSync", 1, "root", "aaaa"), summary, "The smaller root, so the page does not flicker between two");
    }

    @Test
    void serversThatAreDownOrNotReplicatingOrWithoutKvAreNotCounted() {
        var summary = SwarmSnapshot.kvSync(List.of(server(true, "on", "aaaa"), server(false, "on", "zzzz"), server(true, "off", "bbbb"), server(true, null, null)));
        assertEquals(Map.of("replicating", 1, "inSync", 1, "root", "aaaa"), summary);
    }

    @Test
    void whenNoServerIsReplicatingThereIsNoSummary() {
        assertNull(SwarmSnapshot.kvSync(List.of(server(true, "off", "aaaa"), server(true, null, null))));
        assertNull(SwarmSnapshot.kvSync(List.of()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aSwarmWithoutTheKvPluginHasNoKvEntryAndIsAskedOnlyOnce() throws Exception {
        try (var manager = new SwarmManager()) {
            var endpoints = manager.start("plain", 2);
            for (int poll = 0; poll < 2; poll++) {
                var snapshot = SwarmSnapshot.take(manager);
                var swarm = ((List<Map<String, Object>>) snapshot.get("swarms")).getFirst();
                assertNull(swarm.get("kvSync"));
                for (var server : (List<Map<String, Object>>) swarm.get("servers")) { assertNull(server.get("kv"), server.toString()); }
            }
            for (var endpoint : endpoints) { assertTrue(manager.knownWithoutKv(endpoint.port()), endpoint.serverId()); }
        }
    }

    @Test
    void kvReplicationNeedsGossipBecauseItFindsItsPeersThere() {
        var refused = assertThrows(IllegalArgumentException.class, () -> SwarmMain.parseOptions("--plugins-dir", "p", "--kv-replicate"));
        assertTrue(refused.getMessage().contains("--kv-replicate needs --gossip"), refused.getMessage());
    }
}
