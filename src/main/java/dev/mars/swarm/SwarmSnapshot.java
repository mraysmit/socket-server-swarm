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
                out.add(swarm);
            });
            var snapshot = new LinkedHashMap<String, Object>();
            snapshot.put("takenAtMillis", System.currentTimeMillis());
            snapshot.put("swarms", out);
            return snapshot;
        }
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
        if (manager.gossipEnabled()) {
            // A gossip status reply proves the server answers, so the admin-port probe is only needed when it does not.
            var reply = gossip(manager, endpoint, withMembers);
            if (reply != null) { return server(endpoint, true, "Answers gossip status", reply); }
            var status = manager.statusOf(endpoint);
            return server(endpoint, status.alive(), status.detail(), null);
        }
        var status = manager.statusOf(endpoint);
        return server(endpoint, status.alive(), status.detail(), status.alive() ? gossip(manager, endpoint, withMembers) : null);
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
        try {
            var status = manager.execute(endpoint, "gossip", Map.of("operation", "status"));
            if (!"OK".equals(status.status())) { return null; }
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
