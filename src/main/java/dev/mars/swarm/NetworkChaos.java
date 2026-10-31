package dev.mars.swarm;

import dev.mars.client.NetworkFaults;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * What the network between the harness's servers does: which are cut off from which, how slow it is, and how much it
 * loses. Every request one server makes of another asks this policy first (through the host's {@link NetworkFaults}), so
 * a partition separates exactly the servers named, both ways, while each keeps answering the harness and its clients.
 *
 * <p>Rules name servers by ID. A server is found from the port a request goes to; servers added after a rule was made
 * are found too. With no rules, nothing is installed and nothing is slowed.
 */
final class NetworkChaos implements NetworkFaults.Policy {
    /** Every server's ID by its main port, refreshed when a port is not found. */
    private final Supplier<Map<String, List<ServerEndpoint>>> swarms;
    private volatile Map<Integer, String> serverAtPort = Map.of();
    private List<Set<String>> sides = List.of();
    private Map<String, Long> delays = Map.of();
    private Map<String, Integer> losses = Map.of();

    NetworkChaos(Supplier<Map<String, List<ServerEndpoint>>> swarms) { this.swarms = swarms; }

    /**
     * Cuts the swarm into sides: a server on one side can no longer reach one on another, in either direction. Servers
     * on no side are not affected. Replaces any earlier partition.
     */
    synchronized void partition(List<? extends Iterable<String>> named) {
        var parts = new ArrayList<Set<String>>();
        var seen = new LinkedHashSet<String>();
        for (var side : named) {
            var part = new LinkedHashSet<String>();
            for (var id : side) {
                known(id);
                if (!seen.add(id)) { throw new IllegalArgumentException("Server " + id + " is on two sides"); }
                part.add(id);
            }
            if (!part.isEmpty()) { parts.add(Set.copyOf(part)); }
        }
        if (parts.size() < 2) { throw new IllegalArgumentException("A partition needs at least two sides with servers on them"); }
        sides = List.copyOf(parts);
        changed();
    }

    /** Adds {@code millis} to every request to or from each server named; 0 removes the delay. */
    synchronized void delay(Iterable<String> servers, long millis) {
        if (millis < 0 || millis > 60_000) { throw new IllegalArgumentException("Delay must be from 0 to 60000 ms"); }
        var next = new HashMap<>(delays);
        for (var id : servers) {
            known(id);
            if (millis == 0) { next.remove(id); } else { next.put(id, millis); }
        }
        delays = Map.copyOf(next);
        changed();
    }

    /** Loses {@code percent} of the requests to or from each server named; 0 removes the loss. */
    synchronized void loss(Iterable<String> servers, int percent) {
        if (percent < 0 || percent > 100) { throw new IllegalArgumentException("Loss must be from 0 to 100 percent"); }
        var next = new HashMap<>(losses);
        for (var id : servers) {
            known(id);
            if (percent == 0) { next.remove(id); } else { next.put(id, percent); }
        }
        losses = Map.copyOf(next);
        changed();
    }

    /** A healthy network again: no partition, no delay, no loss. */
    synchronized void heal() {
        sides = List.of();
        delays = Map.of();
        losses = Map.of();
        changed();
    }

    /** The rules in force, as the control endpoint reports them. */
    synchronized Map<String, Object> describe() {
        var rules = new LinkedHashMap<String, Object>();
        rules.put("partition", sides.stream().map(side -> side.stream().sorted().toList()).toList());
        rules.put("delayMillis", new java.util.TreeMap<>(delays));
        rules.put("lossPercent", new java.util.TreeMap<>(losses));
        return rules;
    }

    @Override public NetworkFaults.Fault fault(String source, InetSocketAddress destination) {
        String target = serverAt(destination.getPort());
        if (target == null || target.equals(source)) { return NetworkFaults.Fault.NONE; }
        List<Set<String>> cut;
        Map<String, Long> slow;
        Map<String, Integer> lossy;
        synchronized (this) {
            cut = sides;
            slow = delays;
            lossy = losses;
        }
        if (separated(cut, source, target)) { return new NetworkFaults.Fault(0, true); }
        long delay = slow.getOrDefault(source, 0L) + slow.getOrDefault(target, 0L);
        int percent = Math.max(lossy.getOrDefault(source, 0), lossy.getOrDefault(target, 0));
        boolean lost = percent > 0 && ThreadLocalRandom.current().nextInt(100) < percent;
        return delay == 0 && !lost ? NetworkFaults.Fault.NONE : new NetworkFaults.Fault(delay, lost);
    }

    private static boolean separated(List<Set<String>> sides, String a, String b) {
        int sideOfA = -1, sideOfB = -1;
        for (int i = 0; i < sides.size(); i++) {
            if (sides.get(i).contains(a)) { sideOfA = i; }
            if (sides.get(i).contains(b)) { sideOfB = i; }
        }
        return sideOfA >= 0 && sideOfB >= 0 && sideOfA != sideOfB;
    }

    /** The ID of the server on this main port, or null if there is none. */
    String serverAt(int port) {
        var id = serverAtPort.get(port);
        if (id == null) {
            refresh();
            id = serverAtPort.get(port);
        }
        return id;
    }

    private void refresh() {
        var map = new HashMap<Integer, String>();
        swarms.get().values().forEach(servers -> servers.forEach(endpoint -> map.put(endpoint.port(), endpoint.serverId())));
        serverAtPort = Map.copyOf(map);
    }

    private void known(String id) {
        refresh();
        if (!serverAtPort.containsValue(id)) { throw new IllegalArgumentException("Unknown server: " + id); }
    }

    /** Installed only while some rule is in force, so a healthy network costs nothing. */
    private void changed() {
        if (sides.isEmpty() && delays.isEmpty() && losses.isEmpty()) { NetworkFaults.clear(); }
        else { NetworkFaults.install(this); }
    }
}
