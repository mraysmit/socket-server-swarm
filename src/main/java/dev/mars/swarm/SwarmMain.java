package dev.mars.swarm;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;

/** Interactive command-line test harness; EOF and quit close every owned instance. */
public final class SwarmMain {
    public static final String HELP = """
            Swarm commands:
              start <name> <servers>           Start 1-100 servers concurrently on automatic ports
              list                             List all swarms and endpoints
              status [name]                    Check admin status of one or all swarms
              send <name> <text or JSON>        Send a command to every server in a swarm
              test <name> [requests] [clients]  Verify math.add replies (defaults: 1000 requests, 100 clients)
              stop <name>                      Stop one swarm
              stop-all                         Stop every swarm
              help                             Show these commands
              quit                             Stop every swarm and exit
            """;

    public static void main(String[] args) throws IOException {
        if (args.length == 1 && args[0].equals("--help")) {
            System.out.println("Usage: SwarmMain [--plugins-dir <directory>]");
            System.out.print(HELP);
            return;
        }
        Path plugins = null;
        if (args.length != 0) {
            if (args.length != 2 || !args[0].equals("--plugins-dir") || args[1].isBlank()) {
                throw new IllegalArgumentException("Usage: SwarmMain [--plugins-dir <directory>]");
            }
            plugins = Path.of(args[1]);
        }
        var manager = new SwarmManager(plugins);
        var shutdown = new Thread(() -> {
            try { manager.close(); } catch (IOException e) { System.err.println("Swarm shutdown failed: " + e.getMessage()); }
        }, "swarm-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdown);
        try (manager;
             var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            runConsole(manager, input, new PrintWriter(System.out, true));
        } finally {
            try { Runtime.getRuntime().removeShutdownHook(shutdown); }
            catch (IllegalStateException ignored) { /* JVM shutdown is already in progress. */ }
        }
    }

    static void runConsole(SwarmManager manager, BufferedReader input, PrintWriter output) throws IOException {
        output.println("Socket Server Swarm | Java 27 | multiple instances in one JVM");
        output.print(HELP);
        while (true) {
            output.print("swarm> "); output.flush();
            String line = input.readLine();
            if (line == null) { return; }
            line = line.strip();
            if (line.isEmpty()) { continue; }
            String[] words = line.split("\\s+");
            try {
                switch (words[0].toLowerCase(Locale.ROOT)) {
                    case "start" -> {
                        require(words, 3, "start <name> <servers>");
                        var endpoints = manager.start(words[1], number(words[2], "Server count"));
                        output.println("Swarm started | name=" + words[1] + " | servers=" + endpoints.size());
                        endpoints.forEach(output::println);
                    }
                    case "list" -> {
                        require(words, 1, "list");
                        var swarms = manager.list();
                        if (swarms.isEmpty()) { output.println("No running swarms"); }
                        swarms.forEach((name, servers) -> {
                            output.println("Swarm | name=" + name + " | servers=" + servers.size());
                            servers.forEach(output::println);
                        });
                    }
                    case "status" -> {
                        if (words.length > 2) { throw new IllegalArgumentException("Usage: status [name]"); }
                        var names = words.length == 2 ? java.util.List.of(words[1]) : manager.list().keySet();
                        if (names.isEmpty()) { output.println("No running swarms"); }
                        for (var name : names) {
                            for (var status : manager.status(name)) {
                                output.println("Swarm status | name=" + name + " | " + status.endpoint()
                                        + " | alive=" + status.alive() + " | " + status.detail());
                            }
                        }
                    }
                    case "send" -> {
                        String[] parts = line.split("\\s+", 3);
                        require(parts, 3, "send <name> <text or JSON>");
                        manager.send(parts[1], parts[2]).forEach(reply -> output.println("Swarm reply | " + reply));
                    }
                    case "test" -> {
                        if (words.length < 2 || words.length > 4) {
                            throw new IllegalArgumentException("Usage: test <name> [requests] [clients]");
                        }
                        int requests = words.length >= 3 ? number(words[2], "Requests") : 1000;
                        int clients = words.length == 4 ? number(words[3], "Clients") : 100;
                        var result = manager.test(words[1], requests, clients,
                                done -> output.println("Swarm test progress | swarm=" + words[1] + " | completed=" + done + "/" + requests));
                        output.println(result);
                        result.failures().forEach(output::println);
                    }
                    case "stop" -> {
                        require(words, 2, "stop <name>");
                        manager.stop(words[1]);
                        output.println("Swarm stopped | name=" + words[1]);
                    }
                    case "stop-all" -> {
                        require(words, 1, "stop-all"); manager.stopAll(); output.println("All swarms stopped");
                    }
                    case "help" -> { require(words, 1, "help"); output.print(HELP); }
                    case "quit" -> { require(words, 1, "quit"); return; }
                    default -> output.println("Unknown command; enter help");
                }
            } catch (IOException | IllegalArgumentException e) {
                output.println("Error: " + e.getMessage());
            }
        }
    }

    private static int number(String text, String label) {
        try { return Integer.parseInt(text); }
        catch (NumberFormatException e) { throw new IllegalArgumentException(label + " must be an integer"); }
    }
    private static void require(String[] words, int count, String usage) {
        if (words.length != count) { throw new IllegalArgumentException("Usage: " + usage); }
    }
}
