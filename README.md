# Socket Server Swarm

A Java 27 command-line test harness for managing multiple named swarms of
`socket-server-threadpool` servers in one JVM. It references the sibling project
through Maven (`dev.mars:socket-client-server:1.0-SNAPSHOT`) and uses its public
`ServerInstance`, `ServerOptions`, and `TextMessageClient` APIs.

## Build and Run

Prerequisites: JDK 27 and Maven. Check `java -version` and `mvn -version` first.
From this project's root (`socket-server-swarm`), install the sibling dependency,
then build and test the harness:

```powershell
mvn -f ../socket-server-threadpool/pom.xml clean install
mvn clean verify
mvn compile exec:java
```

The first command also runs the server project's tests. Reinstall that dependency
after changing the server project; Maven uses its locally installed JAR, not live
source files from the sibling directory. Maven downloads dependencies on the first
build. `mvn clean verify` creates `target/socket-server-swarm-1.0-SNAPSHOT.jar`.
Use Maven to launch so runtime dependencies are included on the classpath.

## Example Session

Enter these commands in the harness console, one at a time:

```text
start alpha 3
start beta 2
list
status
send alpha {"instruction":"echo","parameters":{"text":"Hello alpha"}}
send beta {"instruction":"echo","parameters":{"text":"Hello beta"}}
send alpha Hello servers
test alpha 1000 100
stop alpha
status beta
stop-all
quit
```

Each swarm starts its servers concurrently. IDs follow the pattern
`alpha-Server-001`, `alpha-Server-002`, and so on. Each server requests main and
admin port `0`; the OS assigns distinct bound TCP ports, which the harness prints.
Server listeners, plugin startup, swarm lifecycle tasks, traffic clients, and the
JVM shutdown hook use virtual threads.
The underlying server also runs its startup plugins, including the UDP address
announcement on port 9002. Server logs are visible in the same terminal.

The `send` command targets every server in the selected swarm. It generates a
different message UUID for each request and uses each server's expected ID to
validate replies. It prints each verified reply, for example:

```text
Swarm reply | messageId=<uuid> | serverId=alpha-Server-001 | connectionId=<id> | status=OK | result="Received: Hello alpha"
```

## Commands

| Command | Behavior |
|---|---|
| `start <name> <servers>` | Start 1–100 instances concurrently in a named swarm |
| `list` | Show every swarm, server ID, and actual main/admin port |
| `status [name]` | Query the admin status of one or all swarms |
| `send <name> <text or JSON>` | Send to each server; plain text invokes `echo` |
| `test <name> [requests] [clients]` | Run a verified load test; defaults: 1,000 requests, concurrency 100 |
| `server <stop\|leave\|restart> <id>` | Act on one server by ID, for example `alpha-Server-002`. `stop` closes it without telling its peers and keeps it listed. `restart` starts it again on the same ports (a gossip server gets a new boot ID). `leave` publishes a graceful gossip departure and needs `--gossip`. |
| `stop <name>` | Close every instance in one swarm |
| `stop-all` | Close every managed instance |
| `help` | Show commands |
| `quit` | Close all swarms and exit |

Command keywords are case-insensitive. Swarm names are case-sensitive, must begin
with a letter, and may contain ASCII letters, digits, dots, underscores, or hyphens.
Duplicate names are rejected. After stopping a swarm, you can reuse its name.
If any server fails during swarm startup, all servers opened by that attempt are
closed; previously running swarms remain available.

The load test sends `echo` requests round-robin across the selected swarm,
using virtual-thread clients and a limit on requests in flight. Each request uses
a unique message UUID, validates the expected server ID, and checks the expected
text result. It uses the production echo protocol and needs no test plugins.
Request counts range from 1 to 100,000; concurrency ranges from 1 to 1,000.
Progress appears every 100 completions, followed by a summary:

```text
Swarm test PASSED | swarm=alpha | requests=1000 | passed=1000 | failed=0 | elapsedMs=<duration>
```

Failures are counted and the first ten details are printed. `send` stops and
reports an error if a server rejects a command or cannot be reached. This first
version is an interactive harness; test failure is reported in its output.
It does not supply a batch-mode exit code or performance benchmark guarantees.

## Gossip

With the gossip plugin built in the sibling `socket-server-threadpool` project, add
`--gossip` (the plugin's default timings) or `--gossip-fast` (200 ms rounds, suspect
after 4 s, dead after 10 s, for demos and tests) and point `--plugins-dir` at the
plugin:

```powershell
mvn compile exec:java "-Dexec.args=--gossip-fast --plugins-dir ../socket-server-threadpool/plugins/gossip-plugin/target"
```

The harness writes the plugin's config file itself and sets `-Dgossip.config` for the
JVM. Each swarm's name becomes its `swarm.id`. The first server of a swarm starts
alone, and the others are given its actual port as their seed. The file is deleted and
the property cleared when the harness closes. Without these options nothing changes.
Check a server with
`send alpha {"instruction":"gossip","parameters":{"operation":"members"}}`.

## Swarm UI

The harness also starts an HTTP endpoint on `http://localhost:8088/`. It serves the
UI page and `GET /api/snapshot`, a JSON view of every swarm and server (ports,
whether each is alive, and the gossip plugin's replies when the plugin is loaded). It
listens on the loopback interface only and rejects other `Host` names.
Opened from that address, the page shows the live swarms and polls the snapshot
every second. Opened as a plain file, it runs a simulated swarm instead. Gossip
ratings appear only for servers that load the gossip plugin.

Click a server in the live page to see its socket activity below the graph: counters
(connections, requests, replies, errors, aborted, by instruction), a requests-per-second
sparkline, and a feed with one row per connection (time, client port, instruction,
message ID, result, milliseconds). Gossip exchanges can be hidden with the checkbox,
since they dominate a gossiping swarm. The data comes from the log lines the servers
already print: the harness copies console output as it is written and parses it per
server (`GET /api/servers/<id>/activity?after=<seq>`). The log has no message bodies, so
the feed shows metadata only, and the harness keeps the last 300 events per server.
Because it reads the console, it records only servers running in this harness, and only
while the UI is enabled (`--no-ui` leaves the console untouched).

In the live page, the Leave, Stop and Restart buttons for the selected server call
`POST /api/servers/<id>/<leave|stop|restart>`, the same operations as the `server`
console command. These requests are refused unless their `Origin` is the harness itself
and they carry an `X-Swarm-UI` header, so another web page cannot trigger them.

Use `--ui-port <port>` to change the port, or `--no-ui` to turn the endpoint off.
If the port is busy, the harness prints a message and continues without the UI.
See [docs/swarm-ui-data-design.md](docs/swarm-ui-data-design.md).

## External Plugins

To make external plugins available to all newly created servers, supply runtime
folders with `--plugins-dir`. Repeat this option for each folder:

```powershell
mvn compile exec:java "-Dexec.args=--plugins-dir ../socket-server-threadpool/plugins/multiply-plugin/target"
```

For multiple folders (replace these with existing directories):

```powershell
mvn compile exec:java "-Dexec.args=--plugins-dir C:/server-plugins/math --plugins-dir C:/server-plugins/text"
```

The harness passes the entire list to every instance it starts. Each server loads
immediate `.jar` files from those folders at startup using one shared class loader,
so plugin dependencies can be stored in another supplied folder. No per-plugin
classpath changes or host rebuild are needed. Duplicate directory paths are
deduplicated; directories are not scanned recursively. Omitting the options uses
built-in and application-classpath providers. Restart instances to reload JARs.

Build the sibling example first if necessary:

```powershell
mvn -f ../socket-server-threadpool/plugins/multiply-plugin/pom.xml clean verify
```

Then enter:

```text
start plugins 2
send plugins {"instruction":"math.multiply","parameters":{"a":6,"b":7}}
quit
```

Each reply includes `result=42`. Every supplied directory must exist; server startup
validates providers and rejects corrupt JARs or duplicate instructions.

## Tests and Cleanup

Run the harness tests from this project root:

```powershell
mvn test "-Dmaven.test.redirectTestOutputToFile=false"
mvn test "-Dtest=SwarmManagerTest" "-Dmaven.test.redirectTestOutputToFile=false"
mvn test "-Dtest=SwarmConsoleTest" "-Dmaven.test.redirectTestOutputToFile=false"
```

Tests start their own servers on automatic ports and close them afterward. They
cover multiple swarms, IDs and ports, command results, load verification, startup
rollback, invalid input, and console handling. Reports are in
`target/surefire-reports/`.

Regression tests also verify that mixed healthy/unreachable targets produce
correct success/failure counts, and that small or non-round request counts always
emit final progress. Swarm tests use the production echo protocol and do not
prescribe which other plugins a server may load.

`quit`, end-of-input, and the JVM shutdown hook close managed swarms and wait for
accepted server work to finish. All instances share one JVM, so this version tests
socket behavior and instance lifecycle rather than process isolation. It manages
only instances it starts; it does not adopt servers discovered through UDP.
