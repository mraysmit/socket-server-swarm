package dev.mars.swarm;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Copies standard error while open, still passing it through to the console, so a test can check what the servers it
 * runs in this JVM reported there; the gossip plugin writes its diagnostics to standard error.
 */
final class StderrLines implements AutoCloseable {
    private final PrintStream original = System.err;
    private final ByteArrayOutputStream copy = new ByteArrayOutputStream();

    StderrLines() {
        var tee = new OutputStream() {
            @Override public void write(int b) {
                original.write(b);
                synchronized (copy) { copy.write(b); }
            }

            @Override public void write(byte[] bytes, int offset, int length) {
                original.write(bytes, offset, length);
                synchronized (copy) { copy.write(bytes, offset, length); }
            }

            @Override public void flush() { original.flush(); }
        };
        System.setErr(new PrintStream(tee, true, StandardCharsets.UTF_8));
    }

    /** The lines written so far that contain {@code text}. */
    List<String> containing(String text) {
        String written;
        synchronized (copy) { written = copy.toString(StandardCharsets.UTF_8); }
        return written.lines().filter(line -> line.contains(text)).toList();
    }

    @Override public void close() { System.setErr(original); }
}
