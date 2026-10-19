# Socket Server Swarm

A Java 27 command-line test harness for managing multiple named swarms of
`socket-server-threadpool` servers in one JVM. It references the sibling project
through Maven (`dev.mars:socket-client-server:1.0-SNAPSHOT`) and uses its public
`ServerInstance`, `ServerOptions`, and `TextMessageClient` APIs.

Getting started: [docs/user-guide.md](docs/user-guide.md) walks through starting a swarm and watching it in the browser.

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
| `add <name> [servers]` | Start more servers (default 1) in a running swarm. They are numbered after the highest ID the swarm has, and with gossip are seeded at its first server. A swarm holds at most 100. |
| `list` | Show every swarm, server ID, and actual main/admin port |
| `status [name]` | Query the admin status of one or all swarms |
| `send <name> <text or JSON>` | Send to each server; plain text invokes `echo` |
| `test <name> [requests] [clients]` | Run a verified load test; defaults: 1,000 requests, concurrency 100; one kept-open connection per client |
| `server <stop\|leave\|restart\|remove> <id>` | Act on one server by ID, for example `alpha-Server-002`. `stop` closes it without telling its peers and keeps it listed. `restart` starts it again on the same ports (a gossip server gets a new boot ID). `leave` publishes a graceful gossip departure and needs `--gossip`. `remove` takes the server out of its swarm for good (with gossip it announces its departure first); the swarm is dropped when its last server is removed. |
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
text result. It uses the production echo protocol and needs no test plugins. Each client keeps one
connection open and reuses it, so a run of 100,000 requests uses about as many connections as it has
clients, not 100,000 (the connections are closed with a reset at the end, which leaves nothing in TIME_WAIT).
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

The page fills the browser window. The server canvas takes all the space left by the
side panels and a dock underneath it, and is built for swarms of up to 100 servers:

- **Zoom:** mouse wheel (or pinch gesture on a trackpad), the `+` and `−` buttons, or the
  `+` and `-` keys. Double-click empty canvas to zoom in there. The zoom percentage is shown
  beside the buttons.
- **Pan:** drag the empty canvas.
- **Fit:** the Fit button or `0` frames every server. Until you move the view yourself, it
  refits automatically when servers are added or removed or the window changes size.
- **Focus:** the Focus button or `F` hides the side panels so the canvas gets the whole window.
- **Find:** the Find box in the header selects a server by part of its ID (`50`, `Server-050`)
  and brings it into view. Choosing a server in the left-hand list also scrolls it into view.
- **Level of detail:** servers numbered inside the circle are drawn when zoomed in; zoomed out,
  numbers and rings drop away and only each server's state and badge remain.
- **Bottom panel height:** drag the bar at the top edge of the Socket activity and Events panel up or down
  (or focus it and use the arrow keys; Shift moves further, Home and End jump to the largest and smallest).
  Double-click the bar to reset. Expand makes the panel as tall as the window allows, leaving a strip of
  canvas, and Restore puts it back. Hide collapses it. The browser remembers the height.
- **Layout:** up to 12 servers per swarm sit on a ring; larger swarms are packed on a spiral so
  100 servers fit in a compact disc.

**Testing with 100 servers (Windows).** Each closed TCP connection sits in TIME_WAIT for two minutes, and
Windows has 16,384 dynamic ports by default, so opening more than roughly 130 connections a second runs the
machine out of ports (new connections then fail with `Address already in use` for every program, not just
this one). To avoid that, a request can ask the server to keep the connection open
(`"connection":"keep-alive"`), and the gossip plugin and this harness reuse connections instead of
opening one per request (see `docs/GOSSIP_PLUGIN.md` and the README in `socket-server-threadpool`). The
page's Conn column shows it: `#9184·15` is the 15th request on connection 9184.

Measured on this machine with 100 servers at the plugin's default 1-second rounds, TIME_WAIT grows by about
5 sockets a second and stays flat. The idle limits are 4 minutes in the plugin and 5 minutes in the server.
They have to be long: a server contacts only about two peers a second, so in a swarm of 100 it meets a given
peer about once a minute, and a connection idle for 20 seconds would be closed before it is used again
(at 20 s and 30 s the run added about 100 sockets a second). The 100 servers settle into a full mesh of about 10,000 open connections (netstat lists
each loopback connection twice, so about 20,000 ESTABLISHED lines), TIME_WAIT stayed at the baseline, and
the swarm was 92 to 94% in sync after four minutes. Quitting the harness then took about three seconds
and added no TIME_WAIT sockets, because idle connections are closed with a reset instead of a closing
handshake (nothing is in flight on them, so nothing is lost). Convergence is slow by design: the plugin only rates a
peer ALIVE after a direct exchange with it, so it takes roughly (servers × ln servers) ÷ (exchanges per
second per server) seconds, and a larger exchange does not speed it up. At `--gossip-fast` (200 ms
rounds, five times the exchange rate) the same 100 servers were 100% in sync after about 90 seconds,
TIME_WAIT never rose above its starting level, and quitting added none.

Other gossip swarms on the same machine count against the same port budget: a 5-server swarm that opens a
connection per exchange holds about 6,000 TIME_WAIT sockets by itself. To watch the sockets, use
`netstat -an | find /c "TIME_WAIT"` and `netstat -an | find /c "ESTABLISHED"`. The harness also helps: for
gossip swarms the snapshot makes one pooled connection per server, and the page polls every 3 seconds
instead of every second once there are more than 32 servers. Any `key=value` the plugin accepts can be set
with `--gossip-option` (it needs `--gossip` or `--gossip-fast`), for example
`--gossip-option round.millis=2000`. All servers in a swarm must run a server build that accepts the keep-alive field.

Gossip returns at most 32 members per page. For a swarm larger than that, a server's "sees live
peers" figure comes from the plugin's own count of ALIVE members, and the selected server's
complete member list is fetched separately (`GET /api/servers/<id>/members`, which follows the
plugin's cursor), so its table, lines and badges cover every member. For these swarms the snapshot leaves
out each server's member list, so the event log cannot name who rates whom; it logs when servers begin or stop
rating a member SUSPECT, DEAD or LEFT, from each server's own counts. In smaller swarms the log names the pair.

Click a server in the live page to see its socket activity in the dock under the canvas: counters
(connections, requests, replies, errors, aborted, by instruction), a requests-per-second
sparkline, and a feed with one row per request (time, client port, connection number, instruction,
message ID, result, milliseconds). A connection kept open for several requests shows as several rows
with the same number, followed by a dot and the request's position on it. Gossip exchanges can be hidden with the checkbox,
since they dominate a gossiping swarm. The data comes from the log lines the servers
already print: the harness copies console output as it is written and parses it per
server (`GET /api/servers/<id>/activity?after=<seq>`). The log has no message bodies, so
the feed shows metadata only, and the harness keeps the last 300 events per server.
Because it reads the console, it records only servers running in this harness, and only
while the UI is enabled (`--no-ui` leaves the console untouched).

Clicking a server on the canvas, or its chip in the swarm list, selects it and shows its socket traffic in the
bottom panel (it switches to the Socket activity tab and opens the panel if it was hidden). Pause feed freezes
the feed so you can read it, and Resume feed catches up from where it stopped.

In the live page, the Leave, Stop, Restart and Remove buttons for the selected server call
`POST /api/servers/<id>/<leave|stop|restart|remove>`, the same operations as the `server`
console command. Add servers in the swarm panel (or `POST /api/swarms/<name>/servers` with
`{"count":3}`) starts more servers in the chosen swarm. These requests are refused unless their `Origin` is the harness itself
and they carry an `X-Swarm-UI` header, so another web page cannot trigger them.

The swarm panel at the top left of the live page does what the console does, so the console is optional.
Type a name and a number of servers and press Start. Choose a swarm in the list to Stop it, or press
Stop all. Send posts a message to every server in the swarm (plain text is echoed; a full JSON request is
sent as it is) and reports how many replied. Load test runs the verified echo test with the request and client
counts you give and shows the result and rate. These use `POST /api/swarms`,
`POST /api/swarms/<name>/<stop|send|test>` and `POST /api/stop-all`, which have the same protection as the
server buttons. The page keeps updating while a large swarm is starting.

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
