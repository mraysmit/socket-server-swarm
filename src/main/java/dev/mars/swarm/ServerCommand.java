package dev.mars.swarm;

import dev.mars.protocol.CommandProtocol;
import dev.mars.protocol.CommandReply;
import dev.mars.protocol.CommandRequest;
import dev.mars.protocol.Json;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Sends one command to one server's main port and reports everything that came back, whether it is a reply, an error
 * reply, bad bytes or no answer at all. It uses a connection of its own, so what the server logs for it is exactly this
 * request and its reply.
 */
final class ServerCommand {
    static final int MAX_INPUT = 16_384;
    private static final int CONNECT_TIMEOUT_MILLIS = 5_000;
    private static final int REPLY_TIMEOUT_MILLIS = 10_000;

    private ServerCommand() { }

    /**
     * Plain text is sent as an {@code echo} command. A JSON object with {@code instruction} and {@code parameters} is sent
     * as the request the console would send. Anything else that starts with a brace is sent exactly as typed, so the
     * server's own complaint about it comes back.
     */
    static Map<String, Object> run(ServerEndpoint endpoint, String input) {
        if (input == null || input.isBlank()) { throw new IllegalArgumentException("Command must not be empty"); }
        if (input.indexOf('\n') >= 0 || input.indexOf('\r') >= 0) { throw new IllegalArgumentException("Command must fit on one line"); }
        if (input.length() > MAX_INPUT) { throw new IllegalArgumentException("Command is too long (limit " + MAX_INPUT + " characters)"); }

        var out = new LinkedHashMap<String, Object>();
        out.put("serverId", endpoint.serverId());
        out.put("port", endpoint.port());
        String sent;
        String note = null;
        UUID id = UUID.randomUUID();
        try {
            CommandRequest request = input.stripLeading().startsWith("{")
                    ? CommandProtocol.submission(id, input)
                    : new CommandRequest(1, id, "echo", Map.of("text", input));
            sent = Json.write(request);
        } catch (IOException e) {
            sent = input;
            note = "Sent as typed, not as a valid request (" + e.getMessage() + ")";
        }
        out.put("sent", sent);
        if (note != null) { out.put("note", note); }

        long began = System.nanoTime();
        String raw = null;
        String failure = null;
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", endpoint.port()), CONNECT_TIMEOUT_MILLIS);
            socket.setSoTimeout(REPLY_TIMEOUT_MILLIS);
            var writer = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
            var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            writer.println(sent);
            if (writer.checkError()) {
                failure = "Could not send the command";
            } else {
                raw = reader.readLine();
                if (raw == null) { failure = "The server closed the connection without replying"; }
            }
        } catch (ConnectException e) {
            failure = "Connection refused on port " + endpoint.port() + ": the server is stopped or not listening";
        } catch (SocketTimeoutException e) {
            failure = "No reply within " + REPLY_TIMEOUT_MILLIS / 1000 + " seconds";
        } catch (IOException e) {
            failure = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        out.put("elapsedMillis", (System.nanoTime() - began) / 1_000_000);
        out.put("raw", raw);

        String status = failure != null ? "FAILED" : "UNPARSED";
        if (raw != null) {
            try {
                CommandReply reply = CommandProtocol.parseReply(raw);
                status = reply.status();
                out.put("replyServerId", reply.serverId());
                out.put("connectionId", reply.connectionId());
                out.put("messageId", String.valueOf(reply.messageId()));
                if (reply.error() != null) {
                    out.put("errorCode", reply.error().code().name());
                    out.put("errorMessage", reply.error().message());
                } else {
                    out.put("result", reply.result());
                }
            } catch (IOException e) {
                // A server rejects a malformed request with an error that has no message ID, which a strict parse refuses.
                // Show what it said anyway.
                try {
                    var root = Json.read(raw);
                    if (root != null && root.isObject() && root.path("error").isObject()) {
                        status = root.path("status").asText("ERROR");
                        out.put("errorCode", root.path("error").path("code").asText(""));
                        out.put("errorMessage", root.path("error").path("message").asText(""));
                    } else {
                        failure = "The reply is not a valid command reply: " + e.getMessage();
                    }
                } catch (IOException notJson) {
                    failure = "The reply is not JSON: " + notJson.getMessage();
                }
            }
        }
        out.put("status", status);
        out.put("ok", "OK".equals(status));
        if (failure != null) { out.put("failure", failure); }
        return out;
    }
}
