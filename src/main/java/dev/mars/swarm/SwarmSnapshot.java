package dev.mars.swarm;

import dev.mars.protocol.CommandReply;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Builds the JSON-ready view of every swarm served to the UI; each server is probed in parallel. */
final class SwarmSnapshot {
    private static final long PROBE_TIMEOUT_SECONDS = 6;
    private static final int MAX_MEMBER_PAGES = 20;
    /**
     * Every probe opens a new TCP connection that then sits in TIME_WAIT for two minutes, and a gossiping swarm already opens
     * several per server per second. Past this swarm size the snapshot leaves out each server's members page (a partial
     * page at that size anyway; the page fetches the selected server's whole list separately).
     */
    static final int MEMBERS_IN_SNAPSHOT_UP_TO = 32;

    private SwarmSnapshot() { }

    static Map<String, Object> take(SwarmManager manager) {
        var swarms = manager.list();
        var probes = new LinkedHashMap<String, List<Future<Map<String, Object>>>>();
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            swarms.forEach((name, endpoints) -> {
                var futures = new ArrayList<Future<Map<String, Object>>>();
                boolean withMembers = endpoints.size() <= MEMBERS_IN_SNAPSHOT_UP_TO;
                for (var endpoint : endpoints) { futures.add(workers.submit(() -> probe(manager, endpoint, withMembers))); }
                probes.put(name, futures);
            });
            var out = new ArrayList<Map<String, Object>>();
            probes.forEach((name, futures) -> {
                var servers = new ArrayList<Map<String, Object>>();
                var endpoints = swarms.get(name);
                for (int i = 0; i < futures.size(); i++) { servers.add(result(futures.get(i), endpoints.get(i))); }
                var swarm = new LinkedHashMap<String, Object>();
                swarm.put("name", name);
                swarm.put("servers", servers);
                var kvSummary = kvSync(servers);
                if (kvSummary != null) { swarm.put("kvSync", kvSummary); }
                out.add(swarm);
            });
            var snapshot = new LinkedHashMap<String, Object>();
            snapshot.put("takenAtMillis", System.currentTimeMillis());
            snapshot.put("swarms", out);
            return snapshot;
        }
    }

    /**
     * How many of a swarm's servers hold the same key-value root, for the page's "kv in sync N of M". Only servers that are up and
     * have kv replication on are counted. The root most of them share is the reference, and a tie goes to the smaller root so the
     * page does not flicker between two. Null when no server is replicating.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> kvSync(List<Map<String, Object>> servers) {
        var counts = new java.util.TreeMap<String, Integer>();
        int replicating = 0;
        for (var server : servers) {
            if (!Boolean.TRUE.equals(server.get("alive")) || !(server.get("kv") instanceof Map<?, ?> kv)) { continue; }
            if (!"on".equals(kv.get("replication")) || !(kv.get("root") instanceof String root)) { continue; }
            replicating++;
            counts.merge(root, 1, Integer::sum);
        }
        if (replicating == 0) { return null; }
        String best = null;
        for (var entry : counts.entrySet()) {
            if (best == null || entry.getValue() > counts.get(best)) { best = entry.getKey(); }
        }
        var summary = new LinkedHashMap<String, Object>();
        summary.put("replicating", replicating);
        summary.put("inSync", counts.get(best));
        summary.put("root", best);
        return summary;
    }

    /** The gossip server's whole member list, followed page by page; a page holds at most 32 members. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> fullMembers(SwarmManager manager, ServerEndpoint endpoint) throws IOException {
        var rows = new ArrayList<Object>();
        String cursor = null;
        int pages = 0;
        do {
            var parameters = new LinkedHashMap<String, Object>();
            parameters.put("operation", "members");
            parameters.put("limit", 32);
            if (cursor != null) { parameters.put("cursor", cursor); }
            var reply = manager.execute(endpoint, "gossip", parameters);
            if (!ok(reply)) {
                throw new IOException(reply.error() == null ? "Gossip members request failed" : reply.error().message());
            }
            var result = (Map<String, Object>) reply.result();
            rows.addAll((List<Object>) result.get("members"));
            cursor = (String) result.get("nextCursor");
            pages++;
        } while (cursor != null && pages < MAX_MEMBER_PAGES);
        var body = new LinkedHashMap<String, Object>();
        body.put("serverId", endpoint.serverId());
        body.put("members", rows);
        body.put("pages", pages);
        body.put("complete", cursor == null);
        return body;
    }

    private static Map<String, Object> probe(SwarmManager manager, ServerEndpoint endpoint, boolean withMembers) {
        if (manager.frozen(endpoint.serverId())) {
            // Asking a frozen server would only wait out a timeout; it is known to be silent.
            var frozen = server(endpoint, false, "Frozen", null);
            frozen.put("frozen", true);
            frozen.put("kv", null);
            return frozen;
        }
        var server = probeServer(manager, endpoint, withMembers);
        server.put("kv", Boolean.TRUE.equals(server.get("alive")) ? kv(manager, endpoint) : null);
        return server;
    }

    /** The key-value plugin's own status reply, passed through unchanged; null when the plugin is absent or does not answer. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> kv(SwarmManager manager, ServerEndpoint endpoint) {
        // Asked once of a server without the plugin, for the same reason as gossip: each ask would show as an error in its activity feed.
        if (manager.knownWithoutKv(endpoint.port())) { return null; }
        try {
            var reply = manager.execute(endpoint, "kv", Map.of("operation", "status"));
            if (!"OK".equals(reply.status())) {
                if (reply.error() != null && reply.error().code().name().equals("UNKNOWN_INSTRUCTION")) { manager.markWithoutKv(endpoint.port()); }
                return null;
            }
            return reply.result() instanceof Map<?, ?> status ? (Map<String, Object>) status : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static Map<String, Object> probeServer(SwarmManager manager, ServerEndpoint endpoint, boolean withMembers) {
        if (manager.gossipEnabled()) {
            // A gossip status reply proves the server answers, so the admin-port probe is only needed when it does not.
            var reply = gossip(manager, endpoint, withMembers);
            if (reply != null) { return server(endpoint, true, "Answers gossip status", reply); }
            var status = manager.statusOf(endpoint);
            return server(endpoint, status.alive(), status.detail(), null);
        }
        // Without gossip, a pooled echo shows that the main port answers. Unlike the admin-port probe it opens no
        // connection of its own, so polling a hundred servers does not add a hundred TIME_WAIT sockets a poll.
        try {
            var ping = manager.execute(endpoint, "echo", Map.of("text", "ping"));
            boolean alive = "OK".equals(ping.status());
            return server(endpoint, alive, alive ? "Answers echo" : "Echo failed", alive ? gossip(manager, endpoint, withMembers) : null);
        } catch (IOException unreachable) {
            return server(endpoint, false, String.valueOf(unreachable.getMessage()), null);
        }
    }

    private static Map<String, Object> result(Future<Map<String, Object>> future, ServerEndpoint endpoint) {
        try {
            return future.get(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return server(endpoint, false, "Probe timed out", null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return server(endpoint, false, "Probe interrupted", null);
        } catch (ExecutionException e) {
            return server(endpoint, false, String.valueOf(e.getCause().getMessage()), null);
        }
    }

    private static Map<String, Object> server(ServerEndpoint endpoint, boolean alive, String detail, Object gossip) {
        var server = new LinkedHashMap<String, Object>();
        server.put("serverId", endpoint.serverId());
        server.put("mainPort", endpoint.port());
        server.put("adminPort", endpoint.adminPort());
        server.put("alive", alive);
        server.put("detail", detail);
        server.put("gossip", gossip);
        return server;
    }

    /** The plugin's own replies, passed through unchanged; null when the plugin is absent or does not answer. */
    private static Map<String, Object> gossip(SwarmManager manager, ServerEndpoint endpoint, boolean withMembers) {
        // A server without the plugin is asked once, not on every poll: each ask would also appear as an error in its activity feed.
        if (manager.knownWithoutGossip(endpoint.port())) { return null; }
        try {
            var status = manager.execute(endpoint, "gossip", Map.of("operation", "status"));
            if (!"OK".equals(status.status())) {
                if (status.error() != null && status.error().code().name().equals("UNKNOWN_INSTRUCTION")) { manager.markWithoutGossip(endpoint.port()); }
                return null;
            }
            var gossip = new LinkedHashMap<String, Object>();
            gossip.put("status", status.result());
            if (withMembers) {
                var members = manager.execute(endpoint, "gossip", Map.of("operation", "members", "limit", 32));
                gossip.put("members", ok(members) ? members.result() : null);
            } else {
                gossip.put("members", null);
                gossip.put("membersOmitted", true);
            }
            return gossip;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static boolean ok(CommandReply reply) { return "OK".equals(reply.status()); }
}
