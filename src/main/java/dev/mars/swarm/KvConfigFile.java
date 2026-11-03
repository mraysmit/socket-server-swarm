package dev.mars.swarm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

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

    /** Turns replication off for the servers of one swarm, for a swarm whose writes are replicated some other way. */
    synchronized void keepLocal(String swarm, int count) throws IOException {
        var lines = new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
        for (int i = 1; i <= count; i++) { lines.add("server." + GossipConfigFile.id(swarm, i) + ".replication=off"); }
        Files.write(file, lines, StandardCharsets.UTF_8);
    }

    /** Turns replication off for one server added to such a swarm. */
    synchronized void keepLocalServer(String serverId) throws IOException {
        var lines = new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
        lines.add("server." + serverId + ".replication=off");
        Files.write(file, lines, StandardCharsets.UTF_8);
    }

    /** Puts one server back to what every other server gets. */
    synchronized void forgetServer(String serverId) throws IOException {
        var lines = new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
        lines.removeIf(line -> line.startsWith("server." + serverId + "."));
        Files.write(file, lines, StandardCharsets.UTF_8);
    }

    /** Puts a swarm's servers back to what every other server gets. */
    synchronized void forget(String swarm) throws IOException {
        var lines = new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
        lines.removeIf(line -> line.startsWith("server." + swarm + "-Server-"));
        Files.write(file, lines, StandardCharsets.UTF_8);
    }

    /** Removes the file and, if it still names this file, the property. */
    @Override public synchronized void close() throws IOException {
        if (closed) { return; }
        closed = true;
        if (file.toString().equals(System.getProperty(PROPERTY))) { System.clearProperty(PROPERTY); }
        Files.deleteIfExists(file);
    }
}
