package dev.mars.swarm;

public record ServerStatus(ServerEndpoint endpoint, boolean alive, String detail) { }
