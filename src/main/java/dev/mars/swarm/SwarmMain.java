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
              add <name> [servers]             Add servers (default 1) to a running swarm
              list                             List all swarms and endpoints
              status [name]                    Check admin status of one or all swarms
              send <name> <text or JSON>        Send a command to every server in a swarm
              test <name> [requests] [clients]  Verify echo replies (defaults: 1000 requests, 100 clients)
              server <stop|leave|restart|remove> <id>  Stop, gracefully leave (gossip), restart or remove one server by ID
              stop <name>                      Stop one swarm
              stop-all                         Stop every swarm
              help                             Show these commands
              quit                             Stop every swarm and exit
            """;

    static final String USAGE = "Usage: SwarmMain [--plugins-dir <directory>]... [--gossip | --gossip-fast]"
            + " [--gossip-option <key=value>]... [--ui-port <port>] [--no-ui]";

    /** Command-line settings; the UI is on by default at {@link UiServer#DEFAULT_PORT}. Gossip is off unless asked for. */
    record Options(java.util.List<Path> pluginDirectories, boolean uiEnabled, int uiPort, GossipSettings gossip) { }

    public static void main(String[] args) throws IOException {
        if (args.length == 1 && args[0].equals("--help")) {
            System.out.println(USAGE);
            System.out.print(HELP);
            return;
        }
        var options = parseOptions(args);
        var activity = new SocketActivity();
        var installed = options.uiEnabled() ? activity.install() : null;
        var manager = options.gossip() == null ? new SwarmManager(options.pluginDirectories())
                : new SwarmManager(options.pluginDirectories(), options.gossip());
        var shutdown = shutdownHook(manager);
        Runtime.getRuntime().addShutdownHook(shutdown);
        try (installed; manager;
             var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
             var ui = startUi(manager, options, activity)) {
            runConsole(manager, input, new PrintWriter(System.out, true));
        } finally {
            try { Runtime.getRuntime().removeShutdownHook(shutdown); }
            catch (IllegalStateException ignored) { /* JVM shutdown is already in progress. */ }
        }
    }

    static Thread shutdownHook(SwarmManager manager) {
        return Thread.ofVirtual().name("swarm-shutdown").unstarted(() -> {
            try { manager.close(); } catch (IOException e) { System.err.println("Swarm shutdown failed: " + e.getMessage()); }
        });
    }

    /** A busy port must not stop the harness, so the UI is skipped with a message instead. */
    private static UiServer startUi(SwarmManager manager, Options options, SocketActivity activity) {
        if (!options.uiEnabled()) { return null; }
        try {
            var ui = new UiServer(manager, options.uiPort(), activity);
            System.out.println("Swarm UI | http://localhost:" + ui.port() + "/");
            return ui;
        } catch (IOException | RuntimeException e) {
            System.out.println("Swarm UI not started on port " + options.uiPort() + ": " + e.getMessage()
                    + " (use --ui-port <port> or --no-ui)");
            return null;
        }
    }

    static java.util.List<Path> parsePluginDirectories(String... args) {
        return parseOptions(args).pluginDirectories();
    }

    static Options parseOptions(String... args) {
        var folders = new java.util.ArrayList<Path>();
        boolean ui = true;
        int port = UiServer.DEFAULT_PORT;
        GossipSettings gossip = null;
        var gossipOptions = new java.util.LinkedHashMap<String, String>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--no-ui" -> ui = false;
                case "--gossip-option" -> {
                    String pair = value(args, i++);
                    int eq = pair.indexOf('=');
                    if (eq < 1 || eq == pair.length() - 1) { throw new IllegalArgumentException("--gossip-option expects key=value, for example round.millis=2000"); }
                    gossipOptions.put(pair.substring(0, eq).strip(), pair.substring(eq + 1).strip());
                }
                case "--gossip", "--gossip-fast" -> {
                    if (gossip != null) { throw new IllegalArgumentException(USAGE); }
                    gossip = args[i].equals("--gossip") ? GossipSettings.defaults() : GossipSettings.fast();
                }
                case "--ui-port" -> port = uiPort(value(args, i++));
                case "--plugins-dir" -> folders.add(Path.of(value(args, i++)));
                default -> throw new IllegalArgumentException(USAGE);
            }
        }
        if (gossip != null && folders.isEmpty()) {
            throw new IllegalArgumentException("--gossip needs --plugins-dir pointing at the gossip plugin");
        }
        if (!gossipOptions.isEmpty()) {
            if (gossip == null) { throw new IllegalArgumentException("--gossip-option needs --gossip or --gossip-fast"); }
            var merged = new java.util.LinkedHashMap<>(gossip.options());
            merged.putAll(gossipOptions);
            gossip = new GossipSettings(gossip.advertiseHost(), merged);
        }
        return new Options(java.util.List.copyOf(folders), ui, port, gossip);
    }

    private static String value(String[] args, int option) {
        if (option + 1 == args.length || args[option + 1].startsWith("--") || args[option + 1].isBlank()) {
            throw new IllegalArgumentException(USAGE);
        }
        return args[option + 1];
    }

    private static int uiPort(String text) {
        try {
            int port = Integer.parseInt(text);
            if (port >= 0 && port <= 65535) { return port; }
        } catch (NumberFormatException ignored) { /* reported below */ }
        throw new IllegalArgumentException("UI port must be an integer from 0 to 65535");
    }

    static void runConsole(SwarmManager manager, BufferedReader input, PrintWriter output) throws IOException {
        output.println("Socket Server Swarm | Java 27 | multiple instances in one JVM");
        output.println(manager.gossipEnabled() ? "Gossip plugin configuration is generated for every swarm" : "Gossip is off (--gossip enables it)");
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
                    case "add" -> {
                        if (words.length < 2 || words.length > 3) { throw new IllegalArgumentException("Usage: add <name> [servers]"); }
                        var added = manager.addServers(words[1], words.length == 3 ? number(words[2], "Server count") : 1);
                        output.println("Servers added | name=" + words[1] + " | servers=" + added.size());
                        added.forEach(output::println);
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
                    case "server" -> {
                        require(words, 3, "server <stop|leave|restart|remove> <id>");
                        switch (words[1].toLowerCase(Locale.ROOT)) {
                            case "stop" -> { manager.stopServer(words[2]); output.println("Server stopped | serverId=" + words[2]); }
                            case "leave" -> { manager.leaveServer(words[2]); output.println("Server left | serverId=" + words[2]); }
                            case "restart" -> output.println("Server restarted | " + manager.restartServer(words[2]));
                            case "remove" -> { manager.removeServer(words[2]); output.println("Server removed | serverId=" + words[2]); }
                            default -> throw new IllegalArgumentException("Usage: server <stop|leave|restart|remove> <id>");
                        }
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
            } catch (IOException | IllegalArgumentException | IllegalStateException e) {
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
