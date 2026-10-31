# User Guide: Start a Swarm and Watch It

This guide gets a swarm running and shows you how to follow it in the browser. For every
option and command, see the [README](../README.md).

## 1. Before you start

You need JDK 27 and Maven. Check them:

```powershell
java -version
mvn -version
```

If `java -version` shows an older JDK, point `JAVA_HOME` at JDK 27 for this terminal. An older JDK
fails to compile with "release version 27 not supported".

## 2. Build (first time, and after changing the server project)

Run these from the `socket-server-swarm` folder:

```powershell
mvn -f ../socket-server-threadpool/pom.xml clean install
mvn -f ../socket-server-threadpool/plugins/gossip-plugin/pom.xml clean verify
```

The first command installs the server the harness uses. The second builds the gossip plugin; skip it
if you only want plain servers without gossip.

## 3. Start the harness

Plain servers:

```powershell
mvn compile exec:java
```

Servers that gossip with each other (use `--gossip-fast` for demos; `--gossip` uses the plugin's
slower default timings):

```powershell
mvn compile exec:java "-Dexec.args=--gossip-fast --plugins-dir ../socket-server-threadpool/plugins/gossip-plugin/target"
```

The harness starts the UI endpoint at `http://localhost:8088/`. You can control everything from the
browser, so you do not need the terminal after this point. (Once servers are running, their logs scroll
through the terminal and bury the prompt. The browser is the place to work.) Use
`--ui-port <port>` for a different port, or `--no-ui` to turn it off. If the port is busy, the
harness says so and carries on without the UI.

## 4. Open the UI

Open **http://localhost:8088/** in your browser. Open it from that address, not by double-clicking
the HTML file: a file opened directly runs a simulated swarm instead of showing yours. A tag in the header reads "Live" for
your swarm and "Simulated swarm" for the simulation.

## 5. Start some servers

In the **Swarms** panel at the top left of the page, type a name (for example `alpha`) and a number of
servers (1 to 100) in the **Start a swarm** box, then press **Start**. The servers appear on the canvas as soon as they are up. A
large swarm can take several seconds to start; the page keeps updating meanwhile. Start a second swarm
the same way if you want one. The name box suggests the next unused name.

The page polls every second, or every 3 seconds once a swarm is larger than 32 servers.

## 6. Watch the operations

- **Select a server:** click it on the canvas, or in the list on the left. Its details and, with
  gossip, its view of the other members open in the inspector on the right, and its socket traffic
  appears in the bottom panel (the panel switches to the Socket activity tab and opens if it was hidden).
- **Gossip ratings:** the outline of each server is its real state. The badge on every other server
  is the selected server's opinion of it (ALIVE, SUSPECT, DEAD, LEFT). A difference between the two is
  worth looking at.
- **Socket activity:** with a server selected, the dock under the canvas shows its connections,
  requests, replies and errors, a requests-per-second graph, and a feed with one row per request. A
  value such as `#9184·15` is the 15th request on connection 9184. Tick the checkbox to hide gossip
  exchanges, which otherwise dominate a gossiping swarm. **Pause feed** freezes the feed so you can read it;
  **Resume feed** catches up from where it stopped.
- **Panel height:** drag the bar along the top edge of the bottom panel to make the feed taller or shorter,
  double-click it to reset, or press **Expand** to give the panel nearly the whole window and **Restore** to put it
  back. The canvas refits as the panel changes.
- **Events tab:** a log of what changed (servers started or stopped, ratings changing, the swarm
  converging). It starts empty after a refresh and does not survive reloading the page.
- **Call traffic:** the curved lines and moving dots on the canvas are the real calls servers make of one another. See
  [Watch the calls](#watch-the-calls) below.
- **Zoom and pan:** mouse wheel or `+` and `-` to zoom, drag the empty canvas to pan, `0` or the Fit
  button to frame everything, `F` or Focus to hide the side panels. The Find box selects a server by
  part of its ID, for example `50`.

### Watch the calls

The harness records every call one server makes of another, and every request from outside the swarm, and the page replays
them. It reads them twice a second, so it shows them about a second late.

- **Dots:** each is one real call, coloured by kind: gossip, KV push, KV compare, clients, other. The smaller dot coming back
  is its reply. A hollow dot is waiting for a reply, and these pile up at a frozen server. A red dot was lost halfway, refused at
  the door or answered with an error, and an amber one timed out. Times are stretched, because a call of a few milliseconds could
  not be seen, but a slower call still moves more slowly.
- **Curved lines:** one per direction, thicker the more calls it has carried in the last few seconds, amber when some failed and
  red when most did. Hover over one for its rate, p50 and p95 times, failures and last call. Click it to see only those calls in
  **Socket activity**; the **Traffic** tab also shows those that never arrived.
- **Straight lines from the selected server:** how it rates its peers, which is opinion and not traffic. **Ratings** hides them.
- **Dashed lines:** network rules. Purple with ✂ is cut by a partition, and amber is slowed or losing requests, labelled with the
  figures the harness applies between those two servers.
- **A red `!` on a line:** the server's rating of that peer disagrees with how its calls to it are going: it rates it ALIVE while
  its calls fail, or SUSPECT or DEAD while they are answered. That is how a gray failure shows itself. These are also listed at the
  top of the **Traffic** tab and in **Events**, and the inspector's **Calls** column shows each member's recent answered and failed calls.
- **The clients box:** in the corner of a swarm, standing for scripts, the page and load tests. The harness polling its own
  servers is left out, so a `put` from a script can be seen entering one server and then fanning out to its peers.
- **Filters:** the chips above the canvas pick the kinds of call, and **Selected only** dims everything that does not touch the
  selected server. The browser remembers the kinds you chose.
- **Going back:** **Hold** stops at this moment, the slider goes back up to a minute, and the speed menu slows the replay down to
  ⅒×. Each tick on the slider is something done to the swarm (a server frozen, a network rule, or a step of a chaos script), and
  pressing one plays from just before it. **Live** returns to the present. While going back, outlines, lines and dots show that
  moment and the panels stay on the present. A server removed since then is not drawn.
- **A large swarm:** only so many dots are drawn, failures first, and zoomed far out only failures, so the lines carry the rest. The
  bar above the canvas says when this is happening. With reduced motion set in your system, dots do not move: failures and
  waiting calls show at the middle of their line.

## 7. Make something happen

Use the **Selected swarm** box in the Swarms panel. Everything in it acts on the swarm chosen in its
drop-down, which follows the server you select; the result of the last action shows under the boxes. If you are unsure what an operation does, press the small
**i** beside its heading for a short explanation (press it again to hide it). The same icon is on the header, the
swarm list, the zoom bar, the Server panel, the server actions in the inspector and each tab of the bottom panel (Socket
activity, Command, Events), where a small note opens beside it; Escape or a click elsewhere closes it.

- **Command to one server:** open the **Command** tab in the bottom panel, pick a server (or click one), type a
  command and press Send. Plain text is sent as an echo; a JSON request such as
  `{"instruction":"gossip","parameters":{"operation":"status"}}` is sent as a request, and the small **i** at the start of the Try line opens a reference of every command with examples you can **Use**
  (put in the box) or **Copy**; the Try buttons fill in
  examples. Every entry shows what was sent and exactly what came back: the result, or the error code and
  message, or why there was no reply (for example "Connection refused" for a stopped server). Use it to see how a
  server handles bad input. The console equivalent is `call alpha-Server-002 hello`.
- **Send:** type a message under **Send to every server** and press Send. Plain text is echoed back. A full JSON request such as
  `{"instruction":"echo","parameters":{"text":"hi"}}` is sent as it is. The panel reports how many servers
  replied OK and shows the first reply, and the requests appear in the socket activity feed.
- **Load test:** set the number of requests and clients and press Run (under **Load test**). It runs a verified echo
  test across the swarm and reports how many passed, how long it took and the rate. Watch the counters and
  graph move while it runs.
- **Add servers:** choose the swarm, enter a number under **Add servers** and press **Add**. They are numbered after the
  highest ID the swarm has and join it (with gossip, through its first server).
- **Remove a server:** select it and press **Remove** in the inspector. It leaves the swarm for good (with
  gossip it announces its departure first, so its peers rate it LEFT). Unlike Stop, it cannot be restarted.
- **Break and repair a server:** select it and use Leave gracefully, Stop or Restart in the inspector.
  Stop closes a server without telling its peers, so with gossip the others move it from ALIVE to SUSPECT
  and then DEAD. Leave announces a graceful departure and shows as LEFT. Restart brings a server back on the
  same ports. (Leave needs gossip.)

The same operations are available in the console if you prefer it: `send alpha hello`,
`test alpha 1000 100`, `server stop alpha-Server-002`.

### Copying key-value pairs between servers

If you started the harness with `--gossip-fast --kv-replicate` and both plugin folders (see the README), the servers copy each
write to the others. Start a swarm, open the **Command** tab, send a kv `put` (the **put** button under **kv**) to one server, then
choose another server in the list and send a `get`: the pair is there. `status` under kv shows that replication is on and how many
pushes the server has made, its root (the same on servers holding the same pairs) and how its comparisons with other servers have gone.
A server that was stopped catches up when you restart it, and under each replicating swarm the page shows **kv: N of M in sync**. Try it:
stop a server with the **Stop** button, put a pair on another, then press **Restart** and watch the line return to all in sync.

## 8. Stop

Choose a swarm and press **Stop**, or press **Stop all**. To end the harness itself, type `quit` in the
terminal or press Ctrl+C, which closes everything and exits.

## Troubleshooting

| Problem | Cause and fix |
|---|---|
| The page shows a simulation, not my servers | You opened the file directly. Open `http://localhost:8088/`. |
| The page cannot connect | The harness is not running, or it used a different `--ui-port`, or the port was busy and the UI was skipped. Read the harness output. |
| No gossip ratings | The harness was started without `--gossip` or `--gossip-fast`, or without `--plugins-dir` pointing at the built gossip plugin. |
| Compile error "release version 27 not supported" | The terminal is using an older JDK. Point `JAVA_HOME` at JDK 27. |
| "Address already in use" with many servers on Windows | Too many sockets in TIME_WAIT. Check with `netstat -an \| find /c "TIME_WAIT"`, stop other swarms, and wait for it to drain. See the 100-server notes in the README. |
| Start says the name is invalid or already exists | A name must start with a letter and use only letters, digits, dots, underscores or hyphens, and each swarm needs a different name. The harness message is shown under the controls. |
| Socket activity is empty | Activity is read from the console output, so it only covers servers started by this harness and only while the UI is on (not with `--no-ui`). |
| No lines or dots between servers | Servers call one another only with `--gossip` or `--gossip-fast`. Check the filter chips above the canvas too. If the harness fails to compile with `cannot find symbol PeerTraffic`, rebuild the server project (step 2). |
