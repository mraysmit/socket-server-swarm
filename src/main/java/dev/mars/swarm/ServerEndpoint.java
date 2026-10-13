package dev.mars.swarm;

/** Actual ports resolved by the operating system for one managed instance. */
public record ServerEndpoint(String serverId, int port, int adminPort) {
    @Override public String toString() {
        return "serverId=" + serverId + " | main=localhost:" + port + " | admin=localhost:" + adminPort;
    }
}
