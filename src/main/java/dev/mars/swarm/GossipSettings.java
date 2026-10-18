package dev.mars.swarm;

import java.util.Map;
import java.util.Set;

/**
 * Turns on the gossip plugin for every swarm the manager starts. The harness owns the swarm ID and the seeds of each
 * server; these settings supply the address the servers advertise and any tuning keys the plugin accepts.
 */
public record GossipSettings(String advertiseHost, Map<String, String> options) {
    private static final Set<String> HARNESS_KEYS = Set.of("swarm.id", "seeds", "advertise.host");

    public GossipSettings {
        if (advertiseHost == null || advertiseHost.isBlank()) { throw new IllegalArgumentException("Advertise host must not be blank"); }
        options = Map.copyOf(options);
        for (var key : options.keySet()) {
            if (HARNESS_KEYS.contains(key) || key.startsWith("server.")) {
                throw new IllegalArgumentException("Gossip option is set by the harness: " + key);
            }
        }
    }

    /** The plugin's own defaults, with servers advertising the loopback address. */
    public static GossipSettings defaults() { return new GossipSettings("127.0.0.1", Map.of()); }

    /** Short rounds and thresholds, so convergence and failure detection happen in seconds. */
    public static GossipSettings fast() {
        return new GossipSettings("127.0.0.1", Map.of("round.millis", "200", "heartbeat.millis", "500",
                "suspect.after.millis", "4000", "dead.after.millis", "10000"));
    }
}
