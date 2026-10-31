package dev.mars.swarm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * The Raft plugin's configuration file for a harness, and the directory its servers keep their logs in. The path of the file is the
 * JVM-wide {@code raft.config} property while this object is open. A server's directory outlives a restart, so it recovers from it, and
 * goes when its swarm is stopped.
 */
final class RaftConfigFile implements AutoCloseable {
    static final String PROPERTY = "raft.config";
    private final Path file;
    private final Path data;
    private boolean closed;

    private RaftConfigFile(Path file, Path data) {
        this.file = file;
        this.data = data;
    }

    static RaftConfigFile open() throws IOException {
        Path data = Files.createTempDirectory("swarm-raft-");
        Path file = Files.createTempFile("swarm-raft-", ".properties");
        // Forward slashes, because a backslash in a properties file is an escape.
        Files.writeString(file, "data.dir=" + data.toString().replace('\\', '/') + System.lineSeparator(), StandardCharsets.UTF_8);
        System.setProperty(PROPERTY, file.toString());
        return new RaftConfigFile(file, data);
    }

    Path dataDirectory() { return data; }

    /** Removes what a stopped server kept, so a later swarm of the same name starts afresh. */
    void forgetServer(String serverId) throws IOException { deleteTree(data.resolve(serverId)); }

    @Override public synchronized void close() throws IOException {
        if (closed) { return; }
        closed = true;
        if (file.toString().equals(System.getProperty(PROPERTY))) { System.clearProperty(PROPERTY); }
        Files.deleteIfExists(file);
        deleteTree(data);
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) { return; }
        try (var paths = Files.walk(root)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) { Files.deleteIfExists(path); }
        }
    }
}
