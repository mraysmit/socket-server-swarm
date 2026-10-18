package dev.mars.swarm;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Per-server record of socket activity, read from the log lines the servers already print: one line when a client
 * connects, one when a command starts and one when its reply is sent. The lines carry no message bodies, so this holds
 * metadata only: time, client address, connection ID, instruction, message ID and reply status.
 */
public final class SocketActivity {
    public static final int EVENTS_PER_SERVER = 300;
    private static final int MAX_SERVERS = 1000;

    public record Event(long seq, long timeMillis, String kind, String instruction, String status,
                        String connectionId, String client, String messageId, String detail) { }

    public record Counters(long connections, long requests, long repliesOk, long repliesError, long aborted,
                           Map<String, Long> byInstruction) { }

    public record View(long lastSeq, long firstSeq, Counters counters, List<Event> events) { }

    private static final class PerServer {
        long seq;
        long connections, requests, repliesOk, repliesError, aborted;
        final Map<String, Long> byInstruction = new TreeMap<>();
        final ArrayDeque<Event> events = new ArrayDeque<>();
    }

    private final Map<String, PerServer> servers = new LinkedHashMap<>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, PerServer> eldest) { return size() > MAX_SERVERS; }
    };

    /** Reads one log line; lines that are not about a server's socket are ignored. */
    public void accept(String line) { accept(line, System.currentTimeMillis()); }

    synchronized void accept(String line, long now) {
        var fields = parse(line);
        if (fields == null) { return; }
        String serverId = fields.get("serverId");
        String head = fields.get("");
        String kind, instruction = null, status = null, detail = null;
        if (head.startsWith("Client connected")) {
            kind = "CONNECTED";
        } else if (head.startsWith("Executing instruction: ")) {
            kind = "REQUEST";
            instruction = head.substring("Executing instruction: ".length()).strip();
        } else if (head.startsWith("Command reply sent: ")) {
            kind = "REPLY";
            status = head.substring("Command reply sent: ".length()).strip();
        } else if (head.startsWith("Request aborted")) {
            kind = "ABORTED";
            detail = head.substring("Request aborted".length()).replaceFirst("^:\\s*", "").strip();
        } else {
            return;
        }
        var server = servers.computeIfAbsent(serverId, id -> new PerServer());
        switch (kind) {
            case "CONNECTED" -> server.connections++;
            case "REQUEST" -> { server.requests++; server.byInstruction.merge(instruction, 1L, Long::sum); }
            case "REPLY" -> { if ("OK".equals(status)) { server.repliesOk++; } else { server.repliesError++; } }
            default -> server.aborted++;
        }
        var event = new Event(++server.seq, now, kind, instruction, status, fields.get("connectionId"),
                fields.get("client"), fields.getOrDefault("messageId", "-").equals("-") ? null : fields.get("messageId"), detail);
        server.events.addLast(event);
        if (server.events.size() > EVENTS_PER_SERVER) { server.events.removeFirst(); }
    }

    /** Events after {@code afterSeq}, oldest first, at most {@code limit}; null if nothing has been seen for the server. */
    public synchronized View view(String serverId, long afterSeq, int limit) {
        var server = servers.get(serverId);
        if (server == null) { return null; }
        var out = new ArrayList<Event>();
        for (var event : server.events) {
            if (event.seq() > afterSeq) { out.add(event); }
        }
        if (out.size() > limit) { out = new ArrayList<>(out.subList(out.size() - limit, out.size())); }
        long first = server.events.isEmpty() ? server.seq + 1 : server.events.peekFirst().seq();
        return new View(server.seq, first, new Counters(server.connections, server.requests, server.repliesOk,
                server.repliesError, server.aborted, Map.copyOf(server.byInstruction)), List.copyOf(out));
    }

    /**
     * Returns the fields of a server log line, with the leading text under the key "", or null when the line has no
     * server ID. Both formats are handled: "[time] Event | k=v | ..." and "Request aborted: reason | k=v | ...".
     */
    static Map<String, String> parse(String line) {
        if (line == null || !line.contains("serverId=")) { return null; }
        String[] parts = line.split(" \\| ");
        var fields = new LinkedHashMap<String, String>();
        String head = parts[0];
        if (head.startsWith("[")) {
            int close = head.indexOf("] ");
            if (close > 0) { head = head.substring(close + 2); }
        }
        fields.put("", head);
        for (int i = 1; i < parts.length; i++) {
            int eq = parts[i].indexOf('=');
            if (eq > 0) { fields.put(parts[i].substring(0, eq), parts[i].substring(eq + 1).strip()); }
        }
        return fields.containsKey("serverId") ? fields : null;
    }

    /** Copies everything written to System.out and System.err to this recorder, line by line, and passes it through. */
    public Installed install() {
        var out = System.out;
        var err = System.err;
        System.setOut(new PrintStream(new LineTee(out, this), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new LineTee(err, this), true, StandardCharsets.UTF_8));
        return new Installed(out, err);
    }

    /** Restores the original streams when closed. */
    public record Installed(PrintStream out, PrintStream err) implements AutoCloseable {
        @Override public void close() { System.setOut(out); System.setErr(err); }
    }

    private static final class LineTee extends OutputStream {
        private final OutputStream target;
        private final SocketActivity activity;
        private final ByteArrayOutputStream line = new ByteArrayOutputStream();

        LineTee(OutputStream target, SocketActivity activity) { this.target = target; this.activity = activity; }

        @Override public synchronized void write(int b) throws java.io.IOException {
            target.write(b);
            collect(b);
        }

        @Override public synchronized void write(byte[] bytes, int offset, int length) throws java.io.IOException {
            target.write(bytes, offset, length);
            for (int i = offset; i < offset + length; i++) { collect(bytes[i]); }
        }

        @Override public void flush() throws java.io.IOException { target.flush(); }

        private void collect(int b) {
            if (b == '\n') {
                String text = line.toString(StandardCharsets.UTF_8);
                line.reset();
                try { activity.accept(text.endsWith("\r") ? text.substring(0, text.length() - 1) : text); }
                catch (RuntimeException ignored) { /* recording must never disturb the console */ }
            } else if (line.size() < 8192) {
                line.write(b);
            }
        }
    }
}
