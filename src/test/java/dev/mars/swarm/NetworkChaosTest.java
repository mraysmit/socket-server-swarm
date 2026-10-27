package dev.mars.swarm;

import dev.mars.client.NetworkFaults;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The rules the harness applies to requests between its servers: partitions both ways, delay, loss, and healing. */
class NetworkChaosTest {
    private static final List<ServerEndpoint> SERVERS = List.of(
            new ServerEndpoint("a-Server-001", 9001, 9101), new ServerEndpoint("a-Server-002", 9002, 9102),
            new ServerEndpoint("a-Server-003", 9003, 9103), new ServerEndpoint("a-Server-004", 9004, 9104));
    private final NetworkChaos chaos = new NetworkChaos(() -> Map.of("a", SERVERS));

    @AfterEach void healed() { chaos.heal(); }

    private NetworkFaults.Fault from(String source, int port) { return chaos.fault(source, new InetSocketAddress("127.0.0.1", port)); }

    @Test void aPartitionLosesEveryRequestBetweenSidesBothWaysAndNoneWithinOne() {
        chaos.partition(List.of(List.of("a-Server-001"), List.of("a-Server-002", "a-Server-003")));
        assertTrue(from("a-Server-001", 9002).lost());
        assertTrue(from("a-Server-002", 9001).lost());
        assertEquals(NetworkFaults.Fault.NONE, from("a-Server-002", 9003), "Same side");
        assertEquals(NetworkFaults.Fault.NONE, from("a-Server-004", 9001), "On no side, so not cut off");
        assertEquals(NetworkFaults.Fault.NONE, from("a-Server-001", 9001), "A server always reaches itself");
        assertEquals(NetworkFaults.Fault.NONE, from("a-Server-001", 7777), "Not a server of the harness");
    }

    @Test void delaysAddUpAndLossOfAllLosesEverything() {
        chaos.delay(List.of("a-Server-001"), 100);
        chaos.delay(List.of("a-Server-002"), 50);
        assertEquals(new NetworkFaults.Fault(150, false), from("a-Server-001", 9002));
        assertEquals(new NetworkFaults.Fault(100, false), from("a-Server-003", 9001), "To a slow server");
        chaos.loss(List.of("a-Server-003"), 100);
        assertTrue(from("a-Server-004", 9003).lost());
        assertTrue(from("a-Server-003", 9004).lost(), "From a lossy server too");
        assertEquals(Map.of("partition", List.of(), "delayMillis", Map.of("a-Server-001", 100L, "a-Server-002", 50L),
                "lossPercent", Map.of("a-Server-003", 100)), chaos.describe());
    }

    @Test void healingRemovesEveryRule() {
        chaos.partition(List.of(List.of("a-Server-001"), List.of("a-Server-002")));
        chaos.delay(List.of("a-Server-003"), 10);
        chaos.heal();
        assertEquals(NetworkFaults.Fault.NONE, from("a-Server-001", 9002));
        assertEquals(NetworkFaults.Fault.NONE, from("a-Server-003", 9004));
    }

    @Test void rulesNameOnlyKnownServersAndSensibleAmounts() {
        assertThrows(IllegalArgumentException.class, () -> chaos.partition(List.of(List.of("a-Server-001"), List.of("a-Server-009"))));
        assertThrows(IllegalArgumentException.class, () -> chaos.partition(List.of(List.of("a-Server-001"))), "One side is no partition");
        assertThrows(IllegalArgumentException.class, () -> chaos.partition(List.of(List.of("a-Server-001"), List.of("a-Server-001"))));
        assertThrows(IllegalArgumentException.class, () -> chaos.delay(List.of("a-Server-001"), -1));
        assertThrows(IllegalArgumentException.class, () -> chaos.loss(List.of("a-Server-001"), 101));
    }
}
