package dev.mars.swarm;

import dev.mars.client.PeerTraffic;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntFunction;

/**
 * Recent calls between the harness's servers, and from clients to them, for the page to replay. Calls one server makes of
 * another are reported by the host's {@link PeerTraffic}. A request a server logs that is neither one of those nor one of
 * the harness's own polls came from a client: a script, the page, or a load test. Marks note what was done to the swarm
 * while the calls were made, so a replay can show what caused what.
 *
 * <p>An observer may run on any request's thread while the manager's lock is held elsewhere, so nothing here waits on the
 * manager: a call keeps the port it went to, and the server there is looked up when the page reads the log.
 */
public final class CallLog implements PeerTraffic.Observer, AutoCloseable {
    /** Enough for a minute of a hundred-server swarm, which makes a few hundred calls a second. */
    public static final int CALLS_KEPT = 30_000;
    private static final int MARKS_KEPT = 500;
    private static final int IN_FLIGHT_KEPT = 5_000;
    private static final int OWN_IDS_KEPT = 20_000;
    private static final int MARK_TEXT_LIMIT = 200;

    /** A finished call. {@code source} is null for a client's call to {@code target}. */
    public record Call(long seq, String source, String target, String instruction, String operation, String messageId,
                       String outcome, String detail, long startMillis, long endMillis, int requestBytes) { }

    /** A call not finished yet: a frozen server is one whose callers pile up here. */
    public record InFlight(String source, String target, String instruction, String operation, String messageId, long startMillis) { }

    /** Something done to the swarm, such as a server frozen or a network rule changed. */
    public record Mark(long seq, long timeMillis, String kind, String text) { }

    public record View(long lastSeq, long firstSeq, long nowMillis, List<Call> calls, List<InFlight> inFlight, List<Mark> marks) { }

    /** A call as recorded; its target is a server ID for a client's call, and otherwise looked up from its port when read. */
    private record Entry(long seq, String source, String target, int port, String instruction, String operation, String messageId,
                         String outcome, String detail, long startMillis, long endMillis, int requestBytes) { }

    private record Open(String source, String target, int port, String instruction, String operation, String messageId, long startMillis) { }

    private final IntFunction<String> serverAtPort;
    private final ArrayDeque<Entry> calls = new ArrayDeque<>();
    private final ArrayDeque<Mark> marks = new ArrayDeque<>();
    private final Map<String, Open> open = bounded(IN_FLIGHT_KEPT);
    /** Message IDs of requests that are not a client's: those servers made of one another, and the harness's polls. */
    private final Map<String, Boolean> ownIds = bounded(OWN_IDS_KEPT);
    private long seq;

    /** {@code serverAtPort} gives the ID of the server on a main port, or null; it is called only when the log is read. */
    public CallLog(IntFunction<String> serverAtPort) { this.serverAtPort = serverAtPort; }

    private static <V> Map<String, V> bounded(int limit) {
        return new LinkedHashMap<>(16, 0.75f, false) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, V> eldest) { return size() > limit; }
        };
    }

    /** Starts being told of the calls servers make of one another. */
    public CallLog install() {
        PeerTraffic.install(this);
        return this;
    }

    @Override public void close() { PeerTraffic.clear(); }

    @Override public synchronized void started(PeerTraffic.Call call) {
        if (call.messageId() == null) { return; }
        ownIds.put(call.messageId(), true);
        open.put(call.messageId(), new Open(call.source(), null, call.destination().getPort(), call.instruction(), call.operation(),
                call.messageId(), call.startedAtMillis()));
    }

    @Override public void finished(PeerTraffic.Call call, PeerTraffic.Outcome outcome, String detail, long elapsedNanos) {
        long end = call.startedAtMillis() + elapsedNanos / 1_000_000;
        synchronized (this) {
            if (call.messageId() != null) { open.remove(call.messageId()); }
            add(call.source(), null, call.destination().getPort(), call.instruction(), call.operation(), call.messageId(),
                    outcome.name(), detail, call.startedAtMillis(), end, call.requestBytes());
        }
    }

    /** A request the harness itself is about to send, which is not to be taken for a client's when the server logs it. */
    public synchronized void harnessRequest(UUID messageId) { ownIds.put(messageId.toString(), true); }

    /** A server logged that it is executing a request. */
    public synchronized void serverRequest(String serverId, String messageId, String instruction, long now) {
        if (messageId == null || ownIds.containsKey(messageId)) { return; }
        open.put(messageId, new Open(null, serverId, 0, instruction, null, messageId, now));
    }

    /** A server logged the end of a request: its reply status, or that it was aborted. */
    public synchronized void serverFinished(String serverId, String messageId, String status, String detail, long now) {
        if (messageId == null || ownIds.containsKey(messageId)) { return; }
        var request = open.get(messageId);
        if (request == null || !serverId.equals(request.target())) { return; }
        open.remove(messageId);
        boolean ok = "OK".equals(status);
        add(null, serverId, 0, request.instruction(), null, messageId, ok ? "OK" : status == null ? "FAILED" : "ERROR_REPLY",
                ok ? null : status == null ? detail : status, request.startMillis(), now, 0);
    }

    /** Notes something done to the swarm. */
    public synchronized void mark(String kind, String text) {
        String shown = text.length() > MARK_TEXT_LIMIT ? text.substring(0, MARK_TEXT_LIMIT) : text;
        marks.addLast(new Mark(++seq, System.currentTimeMillis(), kind, shown));
        if (marks.size() > MARKS_KEPT) { marks.removeFirst(); }
    }

    private void add(String source, String target, int port, String instruction, String operation, String messageId, String outcome,
                     String detail, long start, long end, int requestBytes) {
        calls.addLast(new Entry(++seq, source, target, port, instruction, operation, messageId, outcome, detail, start, end, requestBytes));
        if (calls.size() > CALLS_KEPT) { calls.removeFirst(); }
    }

    /**
     * Calls and marks after {@code afterSeq}, oldest first, at most {@code limit} calls and the newest if there are more, and
     * every call still in flight. A server's call to itself, or to a server that is no longer listed, is left out.
     */
    public View view(long afterSeq, int limit) {
        var newCalls = new ArrayList<Entry>();
        var newMarks = new ArrayList<Mark>();
        List<Open> flying;
        long last, first;
        synchronized (this) {
            for (var it = calls.descendingIterator(); it.hasNext() && newCalls.size() < limit;) {
                var entry = it.next();
                if (entry.seq() <= afterSeq) { break; }
                newCalls.add(entry);
            }
            for (var mark : marks) { if (mark.seq() > afterSeq) { newMarks.add(mark); } }
            flying = List.copyOf(open.values());
            last = seq;
            first = calls.isEmpty() ? seq + 1 : calls.peekFirst().seq();
        }
        var shown = new ArrayList<Call>(newCalls.size());
        for (int i = newCalls.size() - 1; i >= 0; i--) {
            var entry = newCalls.get(i);
            String target = entry.target() != null ? entry.target() : serverAtPort.apply(entry.port());
            if (target == null || target.equals(entry.source())) { continue; }
            shown.add(new Call(entry.seq(), entry.source(), target, entry.instruction(), entry.operation(), entry.messageId(),
                    entry.outcome(), entry.detail(), entry.startMillis(), entry.endMillis(), entry.requestBytes()));
        }
        var inFlight = new ArrayList<InFlight>(flying.size());
        for (var request : flying) {
            String target = request.target() != null ? request.target() : serverAtPort.apply(request.port());
            if (target == null || target.equals(request.source())) { continue; }
            inFlight.add(new InFlight(request.source(), target, request.instruction(), request.operation(), request.messageId(), request.startMillis()));
        }
        return new View(last, first, System.currentTimeMillis(), List.copyOf(shown), List.copyOf(inFlight), List.copyOf(newMarks));
    }
}
