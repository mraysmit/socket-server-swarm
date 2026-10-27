package dev.mars.swarm;

import dev.mars.client.KeepAliveClient;
import dev.mars.protocol.CommandReply;
import dev.mars.protocol.CommandRequest;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Keeps connections to the managed servers open between requests, using the host's {@link KeepAliveClient}, which the
 * gossip and key-value plugins use too. The harness polls every server several times a minute, and a new connection for
 * each poll would leave thousands of sockets in TIME_WAIT on a large swarm. A server that does not know the keep-alive
 * field (an older build) is detected from its reply and served one request per connection from then on.
 */
final class ConnectionPool implements AutoCloseable {
    static final int DEFAULT_MAX_IDLE_PER_SERVER = 4;
    /** Far above any reply the harness reads; a reply is read into a buffer that grows only as far as it needs. */
    private static final int MAX_REPLY_BYTES = 16 << 20;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private final KeepAliveClient client;
    private final AtomicLong opened = new AtomicLong();
    private final AtomicLong reused = new AtomicLong();

    ConnectionPool() { this(DEFAULT_MAX_IDLE_PER_SERVER); }

    /** A load test with many callers at once should keep as many idle connections as it has callers. */
    ConnectionPool(int maxIdlePerServer) {
        if (maxIdlePerServer < 1) { throw new IllegalArgumentException("Idle connections per server must be positive"); }
        var settings = new KeepAliveClient.Settings("Harness", MAX_REPLY_BYTES, Duration.ofSeconds(5),
                Duration.ofSeconds(10), REQUEST_TIMEOUT, maxIdlePerServer, KeepAliveClient.Settings.IDLE_LIMIT,
                KeepAliveClient.Settings.MAX_USES);
        client = new KeepAliveClient(settings, new KeepAliveClient.Listener() {
            @Override public void opened() { opened.incrementAndGet(); }
            @Override public void reused() { reused.incrementAndGet(); }
        });
    }

    /** Sends one command and returns its reply; a server's error reply is returned, not thrown. */
    CommandReply execute(ServerEndpoint endpoint, String instruction, Map<String, Object> parameters) throws IOException {
        var request = new CommandRequest(1, UUID.randomUUID(), instruction, parameters);
        long deadline = System.nanoTime() + REQUEST_TIMEOUT.toNanos();
        var reply = client.exchange(List.of(address(endpoint.port())), KeepAliveClient.envelope(request, true),
                KeepAliveClient.envelope(request, false), deadline).reply();
        // An INVALID_REQUEST reply may carry no message ID if the server could not read the request at all.
        if (!request.messageId().equals(reply.messageId())) {
            throw new IOException("Message ID mismatch: sent " + request.messageId() + ", received " + reply.messageId());
        }
        if (!endpoint.serverId().equals(reply.serverId())) {
            throw new IOException("Server ID mismatch: expected " + endpoint.serverId() + ", received " + reply.serverId());
        }
        return reply;
    }

    private static InetSocketAddress address(int port) { return new InetSocketAddress("localhost", port); }

    /** Connections opened and requests that reused one, for checking that reuse works. */
    long opened() { return opened.get(); }
    long reused() { return reused.get(); }

    /** Whether the server on this port was found to reject the keep-alive field. */
    boolean oneRequestPerConnection(int port) { return client.oneRequestPerConnection(address(port)); }

    @Override public void close() {
        try { client.close(); } catch (IOException alreadyGone) { /* every connection is closed either way */ }
    }
}
