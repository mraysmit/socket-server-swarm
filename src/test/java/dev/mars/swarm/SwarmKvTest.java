package dev.mars.swarm;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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

    @Test
    void kvReplicationNeedsGossipBecauseItFindsItsPeersThere() {
        var refused = assertThrows(IllegalArgumentException.class, () -> SwarmMain.parseOptions("--plugins-dir", "p", "--kv-replicate"));
        assertTrue(refused.getMessage().contains("--kv-replicate needs --gossip"), refused.getMessage());
    }
}
