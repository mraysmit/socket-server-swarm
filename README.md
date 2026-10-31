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
| `call <id> <text or JSON>` | Send one command to one server, running or not, and print the line sent, the raw reply, the status and the time. An error reply, a malformed request, a refused connection and a missing reply are all shown, not hidden. |
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

### Key-value replication

With the key-value plugin loaded as well, `--kv-replicate` makes every server copy its writes to the other servers of its swarm.
It needs gossip, because each server finds its peers by asking its own gossip plugin who it rates alive, so it needs
`--gossip` or `--gossip-fast`, and both plugin folders:

```powershell
mvn compile exec:java "-Dexec.args=--gossip-fast --kv-replicate --plugins-dir ../socket-server-threadpool/plugins/gossip-plugin/target --plugins-dir ../socket-server-threadpool/plugins/kv-plugin/target"
```

Then `start kv 5`, and once the swarm has settled, `call kv-Server-001 {"instruction":"kv","parameters":{"operation":"put","key":"colour","value":"blue"}}`
and the same `get` on any other server: the pair is there. In the page, use the Command tab (the kv entries of the Try row and
the command reference). `kv status` shows whether replication is on, how many pairs the server holds, a root hash that is the same on servers holding the same
pairs, how many pushes it has made, and how its comparisons with other servers have gone. The harness writes the
plugin's configuration file itself, sets `-Dkv.config` for the JVM, and removes both when it closes. Without `--kv-replicate`
nothing changes: each server keeps its own pairs.

A write is pushed to the servers alive at that moment, and every server also compares its pairs with a random other server every
two seconds (`sync.round.millis`), exchanging only what differs. So a push that failed is repaired by the next comparison, a server
that was stopped catches up when it is restarted, a server added to a running swarm receives everything written before it joined, and
a server that starts pulls from a peer as soon as it finds one. Two servers that write the same key at once end up with the same
value, the later write. The page shows `kv: N of M in sync` under each swarm that is replicating (servers up and replicating that hold
the root most of them share), the selected server's kv pairs, root and comparisons in the inspector, and an event when a swarm falls
out of step for more than a moment or settles again. Deletions are remembered for 30 minutes (`namespace.kv.tombstone.retention.millis`),
so a server that is away longer than that can bring a deleted key back.

This is the second phase of replication (see the plugin project's `docs/REPLICATION_PLAN.md`). It is not yet efficient for a large
swarm: every push and every comparison opens a new connection, and a write is pushed to every server alive. Those are the next phase.

## Swarm UI

The harness also starts an HTTP endpoint on `http://localhost:8088/`. It serves the
UI page and `GET /api/snapshot`, a JSON view of every swarm and server (ports,
whether each is alive, and the gossip plugin's replies when the plugin is loaded), and
`GET /api/traffic?after=<seq>`, the calls between servers and from clients since then, with those still
in flight and marks for what was done to the swarm (`POST /api/traffic/mark` adds one). It
listens on the loopback interface only and rejects other `Host` names.
Opened from that address, the page shows the live swarms and polls the snapshot
every second. Opened as a plain file, it runs a simulated swarm instead. Gossip
ratings appear only for servers that load the gossip plugin.

Calls are told apart by who sent them: the host's `PeerTraffic` observer reports each request a server makes of
another, and a request a server logs that is neither one of those nor one of the harness's polls came from a client.
The page replays them about a second late, with filters, network rules and disagreements with gossip ratings drawn on
the lines, and a minute of rewind; see [Watch the calls](docs/user-guide.md#watch-the-calls).

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
  100 servers fit in a disc. No two servers of a swarm sit closer than the **Spacing** slider says, by default leaving a
  clear gap of twice a server's width for the lines and dots between them. `[` and `]` change it too, double-clicking the
  slider resets it, and the browser remembers it. The wider the spacing and the more servers, the further out Fit zooms;
  numbers stay readable until they no longer fit, and zooming in brings back the detail.

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

The **Command** tab in the bottom panel sends one command to any server and shows exactly what came back. Pick the
server in its list (or select one anywhere on the page), type plain text, which is sent as an `echo`, or a JSON
request such as `{"instruction":"gossip","parameters":{"operation":"status"}}`, and press Send. Each entry in the
history shows the line sent, the status (OK, ERROR or FAILED), the time, the connection number, the result or the
error code and message, and the raw reply. A request that is not valid is sent as typed, so the server's own
complaint comes back; a stopped server shows "Connection refused", and a server that does not answer shows a timeout
after 10 seconds. The small **i** at the start of the Try line opens a command reference: every command the harness's plugins
provide (echo, the gossip operations, the key-value operations, the multiply example, and the error cases worth
seeing), each with a one-line example, a short description, and **Use** (puts it in the command box) and **Copy**
buttons; clicking the example text selects it all. Escape, Close or a click outside closes it. The Try buttons fill in the echo command, the gossip operations (`status`, `members`, `leave`), the key-value operations (`put`, `get`, `delete`, `list`) and an unknown instruction. The gossip and key-value ones answer only on servers that loaded those plugins (see the sections on gossip and external plugins); on others the server returns `UNKNOWN_INSTRUCTION`, which is itself worth seeing. The up and down
arrows recall earlier commands, and Clear empties the history (which is also gone when the page is reloaded). It
uses `POST /api/servers/<id>/command` with `{"text":"..."}`, which has the same protection as the other actions and
answers 200 with the whole outcome even when the command failed, 404 for an unknown server and 400 for an empty or
multi-line command.

Clicking a server on the canvas, or its chip in the swarm list, selects it and shows its socket traffic in the
bottom panel (it switches to the Socket activity tab and opens the panel if it was hidden; on the Command tab it stays there and changes the target). Pause feed freezes
the feed so you can read it, and Resume feed catches up from where it stopped.

In the live page, the Leave, Stop, Restart and Remove buttons for the selected server call
`POST /api/servers/<id>/<leave|stop|restart|remove>`, the same operations as the `server`
console command. Add servers in the swarm panel (or `POST /api/swarms/<name>/servers` with
`{"count":3}`) starts more servers in the chosen swarm. These requests are refused unless their `Origin` is the harness itself
and they carry an `X-Swarm-UI` header, so another web page cannot trigger them.

The swarm panel at the top left of the live page does what the console does, so the console is optional.
The panel is split into boxed groups. **Start a swarm** takes a name and a number of servers. **Selected swarm**
starts with a drop-down that chooses the swarm, with Stop for that swarm and Stop all for every swarm, and
everything under it acts on the chosen swarm: **Add servers**, **Send to every server** (plain text is echoed; a
full JSON request is sent as it is; it reports how many replied) and **Load test** (the verified echo test with
the request and client counts you give, with the result and rate). The small **i** beside each operation shows or hides a short explanation of what it does. The same icon appears on
the header (Find, Pause updates), the swarm list, the zoom bar (zoom, pan, Fit, Focus), the Server panel (what each fact and the member table mean), the server actions in
the inspector (Leave gracefully, Stop, Restart, Remove) and each of the Socket activity, Command and Events tabs of
the bottom panel; the simulation's own controls have one too. Those open a small note beside the icon, which closes
when you press the icon again, press Escape or click elsewhere. One icon covers a group of buttons and explains each. The outcome of the last
action shows under the boxes. These use `POST /api/swarms`,
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

The sibling project also has a key-value store plugin (`plugins/kv-plugin`, instruction `kv` with the operations
`put`, `get`, `delete` and `list`; see its README). Build it, then load it into every server, with or without
gossip:

```powershell
mvn -f ../socket-server-threadpool/plugins/kv-plugin/pom.xml clean verify
mvn compile exec:java "-Dexec.args=--plugins-dir ../socket-server-threadpool/plugins/kv-plugin/target"
mvn compile exec:java "-Dexec.args=--gossip-fast --plugins-dir ../socket-server-threadpool/plugins/gossip-plugin/target --plugins-dir ../socket-server-threadpool/plugins/kv-plugin/target"
```

In the page, start a swarm, open the **Command** tab, choose a server and press the **put**, **get**, **list** and
**delete** buttons under **kv** to fill in a request, then Send. Each server keeps its own pairs in memory, so a key
put on `alpha-Server-001` is not found on `alpha-Server-002`, and the pairs are gone when the server stops or is
restarted. Servers that did not load the plugin answer `UNKNOWN_INSTRUCTION`.

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
