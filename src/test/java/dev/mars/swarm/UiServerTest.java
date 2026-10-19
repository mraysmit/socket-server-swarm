package dev.mars.swarm;

import dev.mars.protocol.Json;
import dev.mars.server.ServerInstance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(45)
class UiServerTest {
    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void snapshotReportsEverySwarmAndServer() throws Exception {
        try (var manager = new SwarmManager(); var ui = new UiServer(manager, 0)) {
            var endpoints = manager.start("alpha", 2);
            manager.start("beta", 1);
            var response = get(ui, "/api/snapshot");
            assertEquals(200, response.statusCode());
            assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
            assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith("application/json"));
            var root = Json.read(response.body());
            assertTrue(root.get("takenAtMillis").asLong() > 0);
            var swarms = root.get("swarms");
            assertEquals(2, swarms.size());
            assertEquals("alpha", swarms.get(0).get("name").asText());
            var servers = swarms.get(0).get("servers");
            assertEquals(2, servers.size());
            for (int i = 0; i < 2; i++) {
                assertEquals(endpoints.get(i).serverId(), servers.get(i).get("serverId").asText());
                assertEquals(endpoints.get(i).port(), servers.get(i).get("mainPort").asInt());
                assertEquals(endpoints.get(i).adminPort(), servers.get(i).get("adminPort").asInt());
                assertTrue(servers.get(i).get("alive").asBoolean());
                assertTrue(servers.get(i).get("gossip").isNull(), "No gossip plugin is loaded");
            }
        }
    }

    @Test
    void snapshotShowsAClosedServerAsNotAliveAndEmptyWhenNothingRuns() throws Exception {
        var opened = new ConcurrentLinkedQueue<ServerInstance>();
        try (var manager = new SwarmManager(List.of(), options -> {
            var server = ServerInstance.start(options); opened.add(server); return server;
        }); var ui = new UiServer(manager, 0)) {
            assertEquals(0, Json.read(get(ui, "/api/snapshot").body()).get("swarms").size());
            manager.start("mixed", 2);
            opened.stream().filter(server -> server.getServerId().equals("mixed-Server-002")).findFirst().orElseThrow().close();
            var servers = Json.read(get(ui, "/api/snapshot").body()).get("swarms").get(0).get("servers");
            assertTrue(servers.get(0).get("alive").asBoolean());
            assertFalse(servers.get(1).get("alive").asBoolean());
            assertTrue(servers.get(1).get("gossip").isNull());
        }
    }

    @Test
    void servesThePageAndRejectsOtherPathsAndMethods() throws Exception {
        try (var manager = new SwarmManager(); var ui = new UiServer(manager, 0)) {
            var page = get(ui, "/");
            assertEquals(200, page.statusCode());
            assertTrue(page.headers().firstValue("Content-Type").orElseThrow().startsWith("text/html"));
            assertTrue(page.body().contains("Swarm Observatory"));
            // The page carries its own icon, so a browser never asks for a /favicon.ico this server does not have.
            assertTrue(page.body().contains("<link rel=\"icon\" type=\"image/svg+xml\" href=\"data:image/svg+xml,"),
                    "The page must declare an inline icon");
            assertEquals(200, get(ui, "/index.html").statusCode());
            assertEquals(404, get(ui, "/missing").statusCode());
            assertEquals(404, get(ui, "/api/snapshot/extra").statusCode());
            var post = http.send(HttpRequest.newBuilder(uri(ui, "/api/snapshot"))
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(405, post.statusCode());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"evil.example", "evil.example:8088", "192.168.1.5", "localhost.evil.example"})
    void refusesRequestsWhoseHostIsNotLoopback(String host) throws Exception {
        try (var manager = new SwarmManager(); var ui = new UiServer(manager, 0)) {
            assertEquals(403, rawStatus(ui, host));
            assertEquals(200, rawStatus(ui, "localhost:" + ui.port()));
            assertEquals(200, rawStatus(ui, "127.0.0.1"));
        }
    }

    @Test
    void actionsStopAndRestartAServerAndReportErrors() throws Exception {
        try (var manager = new SwarmManager(); var ui = new UiServer(manager, 0)) {
            manager.start("solo", 2);
            var stop = post(ui, "/api/servers/solo-Server-002/stop", origin(ui), "1");
            assertEquals(200, stop.statusCode(), stop.body());
            assertTrue(Json.read(stop.body()).get("ok").asBoolean());
            var servers = Json.read(get(ui, "/api/snapshot").body()).get("swarms").get(0).get("servers");
            assertTrue(servers.get(0).get("alive").asBoolean());
            assertFalse(servers.get(1).get("alive").asBoolean());

            assertEquals(200, post(ui, "/api/servers/solo-Server-002/restart", origin(ui), "1").statusCode());
            servers = Json.read(get(ui, "/api/snapshot").body()).get("swarms").get(0).get("servers");
            assertTrue(servers.get(1).get("alive").asBoolean());

            var unknown = post(ui, "/api/servers/solo-Server-009/stop", origin(ui), "1");
            assertEquals(404, unknown.statusCode());
            assertFalse(Json.read(unknown.body()).get("ok").asBoolean());
            assertEquals(409, post(ui, "/api/servers/solo-Server-001/leave", origin(ui), "1").statusCode(),
                    "Leave needs gossip, which this harness does not run");
            assertEquals(404, post(ui, "/api/servers/solo-Server-001/explode", origin(ui), "1").statusCode());
            assertEquals(405, get(ui, "/api/servers/solo-Server-001/stop").statusCode());
        }
    }

    @Test
    void actionsRefuseRequestsThatDoNotComeFromThePageThisServerServed() throws Exception {
        try (var manager = new SwarmManager(); var ui = new UiServer(manager, 0)) {
            manager.start("solo", 1);
            String path = "/api/servers/solo-Server-001/stop";
            assertEquals(403, post(ui, path, null, "1").statusCode(), "No Origin");
            assertEquals(403, post(ui, path, "https://evil.example", "1").statusCode(), "Foreign Origin");
            assertEquals(403, post(ui, path, "http://localhost:1", "1").statusCode(), "Another port");
            assertEquals(403, post(ui, path, origin(ui), null).statusCode(), "Missing custom header");
            assertTrue(manager.status("solo").getFirst().alive(), "None of the refused requests stopped the server");
            assertEquals(200, post(ui, path, "http://127.0.0.1:" + ui.port(), "1").statusCode());
            assertFalse(manager.status("solo").getFirst().alive());
        }
    }

    @Test
    void activityEndpointServesAServersSocketActivityAndOnlyNewEvents() throws Exception {
        var recorder = new SocketActivity();
        try (var ignored = recorder.install(); var manager = new SwarmManager(); var ui = new UiServer(manager, 0, recorder)) {
            manager.start("solo", 2);
            manager.send("solo", "one");
            manager.send("solo", "two");
            var first = Json.read(get(ui, "/api/servers/solo-Server-001/activity").body());
            assertTrue(first.get("available").asBoolean());
            assertEquals("solo-Server-001", first.get("serverId").asText());
            assertEquals(2, first.get("counters").get("requests").asInt());
            assertEquals(2, first.get("counters").get("byInstruction").get("echo").asInt());
            assertEquals(6, first.get("events").size());
            long last = first.get("lastSeq").asLong();
            assertEquals(6, last);

            manager.send("solo", "three");
            var next = Json.read(get(ui, "/api/servers/solo-Server-001/activity?after=" + last).body());
            assertEquals(3, next.get("events").size());
            assertEquals(last + 1, next.get("events").get(0).get("seq").asLong());
            assertEquals(3, next.get("counters").get("requests").asInt());
            assertEquals(0, Json.read(get(ui, "/api/servers/solo-Server-001/activity?after=999").body()).get("events").size());
            assertEquals(9, Json.read(get(ui, "/api/servers/solo-Server-001/activity?after=abc").body()).get("events").size(),
                    "A bad after value counts as 0");

            assertEquals(404, get(ui, "/api/servers/solo-Server-009/activity").statusCode());
            assertEquals(404, get(ui, "/api/servers/solo-Server-001/other").statusCode());
            assertEquals(405, get(ui, "/api/servers/solo-Server-001/stop").statusCode());
        }
    }

    @Test
    void activityEndpointSaysSoWhenNothingIsRecordedOrAServerHasBeenQuiet() throws Exception {
        try (var manager = new SwarmManager(); var ui = new UiServer(manager, 0)) {
            manager.start("solo", 1);
            var body = Json.read(get(ui, "/api/servers/solo-Server-001/activity").body());
            assertFalse(body.get("available").asBoolean());
            assertNull(body.get("events"));
        }
        try (var manager = new SwarmManager(); var ui = new UiServer(manager, 0, new SocketActivity())) {
            manager.start("quiet", 1);
            var body = Json.read(get(ui, "/api/servers/quiet-Server-001/activity").body());
            assertTrue(body.get("available").asBoolean());
            assertEquals(0, body.get("events").size());
            assertEquals(0, body.get("counters").get("requests").asInt());
        }
    }

    @Test
    void fullMembersEndpointRejectsUnknownServersAndServersWithoutGossip() throws Exception {
        try (var manager = new SwarmManager(); var ui = new UiServer(manager, 0)) {
            manager.start("solo", 1);
            assertEquals(404, get(ui, "/api/servers/solo-Server-009/members").statusCode());
            var noGossip = get(ui, "/api/servers/solo-Server-001/members");
            assertEquals(502, noGossip.statusCode(), "The echo-only server has no gossip instruction");
            assertFalse(Json.read(noGossip.body()).get("ok").asBoolean());
            assertEquals(404, post(ui, "/api/servers/solo-Server-001/members", origin(ui), "1").statusCode(),
                    "Members is read-only, so a POST to it is not an action");
        }
    }

    @Test
    void closeReleasesThePort() throws Exception {
        int port;
        try (var manager = new SwarmManager()) {
            var ui = new UiServer(manager, 0);
            port = ui.port();
            assertTrue(port > 0);
            ui.close();
        }
        try (var socket = new ServerSocket(port)) { assertEquals(port, socket.getLocalPort()); }
    }

    @Test
    void aBusyPortIsReportedToTheCaller() throws Exception {
        try (var manager = new SwarmManager(); var busy = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            assertThrows(IOException.class, () -> new UiServer(manager, busy.getLocalPort()));
        }
    }

    @Test
    void parsesUiOptionsWithTheUiOnByDefault() {
        var defaults = SwarmMain.parseOptions();
        assertTrue(defaults.uiEnabled());
        assertEquals(UiServer.DEFAULT_PORT, defaults.uiPort());
        assertTrue(defaults.pluginDirectories().isEmpty());
        var custom = SwarmMain.parseOptions("--ui-port", "9100", "--plugins-dir", "one", "--no-ui");
        assertFalse(custom.uiEnabled());
        assertEquals(9100, custom.uiPort());
        assertEquals(List.of(Path.of("one")), custom.pluginDirectories());
        assertEquals(0, SwarmMain.parseOptions("--ui-port", "0").uiPort());
        for (String[] args : List.of(new String[]{"--ui-port"}, new String[]{"--ui-port", "abc"},
                new String[]{"--ui-port", "70000"}, new String[]{"--ui-port", "-1"}, new String[]{"--ui-port", "--no-ui"})) {
            assertThrows(IllegalArgumentException.class, () -> SwarmMain.parseOptions(args));
        }
    }

    private HttpResponse<String> postJson(UiServer ui, String path, String json) throws Exception {
        var request = HttpRequest.newBuilder(uri(ui, path)).header("Origin", origin(ui)).header(UiServer.ACTION_HEADER, "1")
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)).build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void thePageCanStartASwarmSendToItLoadTestItAndStopIt() throws Exception {
        try (var manager = new SwarmManager(); var ui = new UiServer(manager, 0)) {
            var started = postJson(ui, "/api/swarms", "{\"name\":\"alpha\",\"servers\":3}");
            assertEquals(200, started.statusCode(), started.body());
            assertEquals(3, Json.read(started.body()).get("servers").size());
            assertEquals(3, Json.read(get(ui, "/api/snapshot").body()).get("swarms").get(0).get("servers").size());

            var sent = postJson(ui, "/api/swarms/alpha/send", "{\"text\":\"hello\"}");
            assertEquals(200, sent.statusCode(), sent.body());
            var replies = Json.read(sent.body()).get("replies");
            assertEquals(3, replies.size());
            for (var reply : replies) {
                assertEquals("OK", reply.get("status").asText());
                assertEquals("Received: hello", reply.get("result").asText());
            }

            var tested = postJson(ui, "/api/swarms/alpha/test", "{\"requests\":60,\"concurrency\":6}");
            assertEquals(200, tested.statusCode(), tested.body());
            var result = Json.read(tested.body());
            assertTrue(result.get("ok").asBoolean());
            assertEquals(60, result.get("passed").asInt());
            assertEquals(0, result.get("failed").asInt());

            assertEquals(200, postJson(ui, "/api/swarms/alpha/stop", "{}").statusCode());
            assertEquals(0, Json.read(get(ui, "/api/snapshot").body()).get("swarms").size());
        }
    }

    @Test
    void thePageCanAddServersToASwarmAndRemoveOne() throws Exception {
        try (var manager = new SwarmManager(); var ui = new UiServer(manager, 0, new SocketActivity())) {
            manager.start("grow", 2);
            var added = postJson(ui, "/api/swarms/grow/servers", "{\"count\":2}");
            assertEquals(200, added.statusCode(), added.body());
            assertEquals(2, Json.read(added.body()).get("servers").size());
            assertEquals(4, Json.read(get(ui, "/api/snapshot").body()).get("swarms").get(0).get("servers").size());
            assertEquals(200, postJson(ui, "/api/swarms/grow/servers", "").statusCode(), "No body adds one server");
            assertEquals(5, manager.list().get("grow").size());

            var removed = postJson(ui, "/api/servers/grow-Server-002/remove", "");
            assertEquals(200, removed.statusCode(), removed.body());
            var ids = new java.util.ArrayList<String>();
            for (var server : Json.read(get(ui, "/api/snapshot").body()).get("swarms").get(0).get("servers")) { ids.add(server.get("serverId").asText()); }
            assertEquals(List.of("grow-Server-001", "grow-Server-003", "grow-Server-004", "grow-Server-005"), ids);
            assertEquals(404, postJson(ui, "/api/servers/grow-Server-002/remove", "").statusCode(), "Already removed");
            assertEquals(404, postJson(ui, "/api/swarms/nope/servers", "").statusCode());
            assertEquals(400, postJson(ui, "/api/swarms/grow/servers", "{\"count\":0}").statusCode());
            assertEquals(400, postJson(ui, "/api/swarms/grow/servers", "{\"count\":99}").statusCode(), "Past 100 servers");
            assertEquals(4, manager.list().get("grow").size(), "Refused requests changed nothing");

            var foreign = HttpRequest.newBuilder(uri(ui, "/api/servers/grow-Server-001/remove")).header("Origin", "https://evil.example")
                    .header(UiServer.ACTION_HEADER, "1").POST(HttpRequest.BodyPublishers.noBody()).build();
            assertEquals(403, http.send(foreign, HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(4, manager.list().get("grow").size());
        }
    }

    @Test
    void stopAllClosesEverySwarm() throws Exception {
        try (var manager = new SwarmManager(); var ui = new UiServer(manager, 0)) {
            manager.start("alpha", 1);
            manager.start("beta", 1);
            assertEquals(200, postJson(ui, "/api/stop-all", "").statusCode());
            assertEquals(0, manager.list().size());
            assertEquals(200, postJson(ui, "/api/stop-all", "").statusCode(), "Stopping nothing is fine");
        }
    }

    @Test
    void swarmActionsRejectBadInputWithoutChangingAnything() throws Exception {
        try (var manager = new SwarmManager(); var ui = new UiServer(manager, 0)) {
            manager.start("alpha", 1);
            assertEquals(400, postJson(ui, "/api/swarms", "{\"name\":\"alpha\",\"servers\":2}").statusCode(), "Duplicate name");
            assertEquals(400, postJson(ui, "/api/swarms", "{\"name\":\"9bad\",\"servers\":2}").statusCode(), "Bad name");
            assertEquals(400, postJson(ui, "/api/swarms", "{\"name\":\"beta\",\"servers\":0}").statusCode(), "Too few");
            assertEquals(400, postJson(ui, "/api/swarms", "{\"name\":\"beta\",\"servers\":101}").statusCode(), "Too many");
            assertEquals(400, postJson(ui, "/api/swarms", "{\"name\":\"beta\",\"servers\":\"3\"}").statusCode(), "Count must be a number");
            assertEquals(400, postJson(ui, "/api/swarms", "{\"servers\":3}").statusCode(), "Missing name");
            assertEquals(400, postJson(ui, "/api/swarms", "not json").statusCode(), "Not JSON");
            assertEquals(400, postJson(ui, "/api/swarms", "[1]").statusCode(), "Not an object");
            assertEquals(400, postJson(ui, "/api/swarms", "{\"name\":\"" + "x".repeat(9000) + "\",\"servers\":1}").statusCode(), "Too large");
            assertEquals(400, postJson(ui, "/api/swarms/alpha/send", "{}").statusCode(), "No message");
            assertEquals(400, postJson(ui, "/api/swarms/alpha/test", "{\"requests\":0}").statusCode(), "No requests");
            assertEquals(404, postJson(ui, "/api/swarms/nope/stop", "{}").statusCode(), "Unknown swarm");
            assertEquals(404, postJson(ui, "/api/swarms/nope/send", "{\"text\":\"x\"}").statusCode(), "Unknown swarm");
            assertEquals(404, postJson(ui, "/api/swarms/alpha/explode", "{}").statusCode());
            assertEquals(405, get(ui, "/api/swarms").statusCode());
            assertEquals(List.of("alpha"), List.copyOf(manager.list().keySet()), "Only the first swarm exists");
        }
    }

    @Test
    void swarmActionsRefuseRequestsThatDoNotComeFromThePageThisServerServed() throws Exception {
        try (var manager = new SwarmManager(); var ui = new UiServer(manager, 0)) {
            for (String path : List.of("/api/swarms", "/api/stop-all")) {
                var foreign = HttpRequest.newBuilder(uri(ui, path)).header("Origin", "https://evil.example")
                        .header(UiServer.ACTION_HEADER, "1").POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"x\",\"servers\":1}")).build();
                assertEquals(403, http.send(foreign, HttpResponse.BodyHandlers.ofString()).statusCode(), path);
                var noHeader = HttpRequest.newBuilder(uri(ui, path)).header("Origin", origin(ui))
                        .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"x\",\"servers\":1}")).build();
                assertEquals(403, http.send(noHeader, HttpResponse.BodyHandlers.ofString()).statusCode(), path);
            }
            assertEquals(0, manager.list().size(), "Neither refused request started a swarm");
        }
    }

    @Test
    void theSnapshotKeepsAnsweringWhileALargeSwarmIsStillStarting() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        try (var manager = new SwarmManager(List.of(), options -> {
            entered.countDown();
            try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return ServerInstance.start(options);
        }); var ui = new UiServer(manager, 0)) {
            var starting = java.util.concurrent.CompletableFuture.runAsync(() -> {
                try { manager.start("slow", 2); } catch (IOException e) { throw new IllegalStateException(e); }
            });
            try {
                assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS));
                var response = get(ui, "/api/snapshot");
                assertEquals(200, response.statusCode(), "The snapshot must not wait for the swarm that is starting");
                assertEquals(0, Json.read(response.body()).get("swarms").size());
            } finally {
                release.countDown();
            }
            starting.get(30, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(2, Json.read(get(ui, "/api/snapshot").body()).get("swarms").get(0).get("servers").size());
        }
    }

    private HttpResponse<String> get(UiServer ui, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(ui, path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String origin(UiServer ui) { return "http://localhost:" + ui.port(); }

    private HttpResponse<String> post(UiServer ui, String path, String origin, String header) throws Exception {
        var request = HttpRequest.newBuilder(uri(ui, path)).POST(HttpRequest.BodyPublishers.noBody());
        if (origin != null) { request.header("Origin", origin); }
        if (header != null) { request.header(UiServer.ACTION_HEADER, header); }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static URI uri(UiServer ui, String path) { return URI.create("http://localhost:" + ui.port() + path); }

    /** The JDK client will not set Host itself, so this sends the request by hand. */
    private static int rawStatus(UiServer ui, String host) throws IOException {
        try (var socket = new Socket(java.net.InetAddress.getLoopbackAddress(), ui.port())) {
            socket.setSoTimeout(5000);
            socket.getOutputStream().write(("GET /api/snapshot HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            String statusLine = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII)).readLine();
            return Integer.parseInt(statusLine.split(" ")[1]);
        }
    }
}
