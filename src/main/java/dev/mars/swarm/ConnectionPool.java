package dev.mars.swarm;

import dev.mars.protocol.CommandProtocol;
import dev.mars.protocol.CommandReply;
import dev.mars.protocol.Json;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Keeps connections to the managed servers open between requests, using the command protocol's
 * {@code "connection":"keep-alive"}. The harness polls every server several times a minute, and a new connection for
 * each poll would leave thousands of sockets in TIME_WAIT on a large swarm.
 */
final class ConnectionPool implements AutoCloseable {
    /** Shorter than the server's 30 second idle limit, so a pooled connection is normally still open. */
    private static final long IDLE_LIMIT_NANOS = TimeUnit.SECONDS.toNanos(20);
    private static final int MAX_IDLE_PER_SERVER = 4;
    /** The server closes a kept-open connection after 1,000 requests. */
    private static final int MAX_USES = 900;
    private static final int CONNECT_TIMEOUT_MILLIS = 5000;
    private static final int READ_TIMEOUT_MILLIS = 10000;

    private static final class Connection {
        final Socket socket;
        final BufferedReader in;
        final OutputStream out;
        long idleSince;
        int uses;

        Connection(Socket socket) throws IOException {
            this.socket = socket;
            this.in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            this.out = socket.getOutputStream();
        }

        void close() {
            try { socket.close(); } catch (IOException alreadyClosed) { /* nothing more to release */ }
        }
    }

    /** A pooled connection the server closed while it sat idle; the request never reached it. */
    private static final class Stale extends IOException {
        Stale(IOException cause) { super(cause); }
    }

    private final Map<Integer, Deque<Connection>> idle = new HashMap<>();
    private boolean closed;
    private long opened;
    private long reused;

    /** Sends one command and returns its reply; a server's error reply is returned, not thrown. */
    CommandReply execute(ServerEndpoint endpoint, String instruction, Map<String, Object> parameters) throws IOException {
        var messageId = UUID.randomUUID();
        var envelope = new LinkedHashMap<String, Object>();
        envelope.put("version", 1);
        envelope.put("messageId", messageId);
        envelope.put("instruction", instruction);
        envelope.put("parameters", parameters);
        envelope.put(CommandProtocol.CONNECTION_FIELD, CommandProtocol.KEEP_ALIVE);
        String line = Json.write(envelope);

        var pooled = take(endpoint.port());
        if (pooled != null) {
            try { return converse(pooled, endpoint, messageId, line); }
            catch (Stale stale) { /* fall through and send it again over a new connection */ }
        }
        var socket = new Socket();
        try {
            socket.connect(new InetSocketAddress("localhost", endpoint.port()), CONNECT_TIMEOUT_MILLIS);
        } catch (IOException e) {
            try { socket.close(); } catch (IOException ignored) { /* never connected */ }
            throw e;
        }
        synchronized (this) { opened++; }
        return converse(new Connection(socket), endpoint, messageId, line);
    }

    private CommandReply converse(Connection connection, ServerEndpoint endpoint, UUID messageId, String line) throws IOException {
        boolean reusedConnection = connection.uses > 0;
        boolean clean = false;
        try {
            if (reusedConnection) {
                // Nothing should be waiting on an idle connection; data that is would be read as this reply.
                if (connection.in.ready()) { throw new Stale(new IOException("Unsolicited data on an idle connection")); }
                synchronized (this) { reused++; }
            }
            connection.socket.setSoTimeout(READ_TIMEOUT_MILLIS);
            try {
                connection.out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                connection.out.flush();
            } catch (IOException writeFailed) {
                throw reusedConnection ? new Stale(writeFailed) : writeFailed;
            }
            String text;
            try {
                text = connection.in.readLine();
            } catch (SocketTimeoutException timedOut) {
                throw timedOut;
            } catch (IOException readFailed) {
                throw reusedConnection ? new Stale(readFailed) : readFailed;
            }
            if (text == null) {
                var closedBeforeReply = new IOException("Server closed the connection without replying");
                throw reusedConnection ? new Stale(closedBeforeReply) : closedBeforeReply;
            }
            CommandReply reply = CommandProtocol.parseReply(text);
            if (!messageId.equals(reply.messageId())) {
                throw new IOException("Message ID mismatch: sent " + messageId + ", received " + reply.messageId());
            }
            if (!endpoint.serverId().equals(reply.serverId())) {
                throw new IOException("Server ID mismatch: expected " + endpoint.serverId() + ", received " + reply.serverId());
            }
            connection.uses++;
            clean = true;
            return reply;
        } finally {
            if (clean) { release(endpoint.port(), connection); } else { connection.close(); }
        }
    }

    private synchronized Connection take(int port) {
        prune();
        var queue = idle.get(port);
        if (queue == null) { return null; }
        var connection = queue.pollLast();
        if (queue.isEmpty()) { idle.remove(port); }
        return connection;
    }

    private synchronized void release(int port, Connection connection) {
        if (closed || connection.uses >= MAX_USES || connection.socket.isClosed()) { connection.close(); return; }
        connection.idleSince = System.nanoTime();
        var queue = idle.computeIfAbsent(port, key -> new ArrayDeque<>());
        queue.addLast(connection);
        while (queue.size() > MAX_IDLE_PER_SERVER) { queue.pollFirst().close(); }
        prune();
    }

    private void prune() {
        long now = System.nanoTime();
        for (var queues = idle.values().iterator(); queues.hasNext();) {
            var queue = queues.next();
            while (!queue.isEmpty() && now - queue.peekFirst().idleSince > IDLE_LIMIT_NANOS) { queue.pollFirst().close(); }
            if (queue.isEmpty()) { queues.remove(); }
        }
    }

    /** Connections opened and requests that reused one, for checking that reuse works. */
    synchronized long opened() { return opened; }
    synchronized long reused() { return reused; }

    /** Idle connections carry nothing, so they are reset rather than closed with a handshake that would park their ports in TIME_WAIT. */
    @Override public synchronized void close() {
        closed = true;
        idle.values().forEach(queue -> queue.forEach(connection -> {
            try { connection.socket.setSoLinger(true, 0); } catch (IOException cannotReset) { /* an ordinary close then */ }
            connection.close();
        }));
        idle.clear();
    }
}
