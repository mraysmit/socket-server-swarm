package dev.mars.swarm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The key-value plugin's configuration file for a harness. It turns replication on for every server in the JVM, and its path
 * is the JVM-wide {@code kv.config} property while this object is open.
 */
final class KvConfigFile implements AutoCloseable {
    static final String PROPERTY = "kv.config";
    private final Path file;
    private boolean closed;

    private KvConfigFile(Path file) { this.file = file; }

    static KvConfigFile open() throws IOException {
        Path file = Files.createTempFile("swarm-kv-", ".properties");
        Files.writeString(file, "replication=on" + System.lineSeparator(), StandardCharsets.UTF_8);
        System.setProperty(PROPERTY, file.toString());
        return new KvConfigFile(file);
    }

    Path path() { return file; }

    /** Removes the file and, if it still names this file, the property. */
    @Override public synchronized void close() throws IOException {
        if (closed) { return; }
        closed = true;
        if (file.toString().equals(System.getProperty(PROPERTY))) { System.clearProperty(PROPERTY); }
        Files.deleteIfExists(file);
    }
}
