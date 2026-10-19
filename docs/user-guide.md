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
servers (1 to 100), then press **Start**. The servers appear on the canvas as soon as they are up. A
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
- **Zoom and pan:** mouse wheel or `+` and `-` to zoom, drag the empty canvas to pan, `0` or the Fit
  button to frame everything, `F` or Focus to hide the side panels. The Find box selects a server by
  part of its ID, for example `50`.

## 7. Make something happen

Use the controls in the Swarms panel. They act on the swarm chosen in the drop-down, which follows the
server you select.

- **Send:** type a message and press Send. Plain text is echoed back. A full JSON request such as
  `{"instruction":"echo","parameters":{"text":"hi"}}` is sent as it is. The panel reports how many servers
  replied OK and shows the first reply, and the requests appear in the socket activity feed.
- **Load test:** set the number of requests and clients and press Load test. It runs a verified echo
  test across the swarm and reports how many passed, how long it took and the rate. Watch the counters and
  graph move while it runs.
- **Add servers:** choose the swarm, enter a number and press **Add servers**. They are numbered after the
  highest ID the swarm has and join it (with gossip, through its first server).
- **Remove a server:** select it and press **Remove** in the inspector. It leaves the swarm for good (with
  gossip it announces its departure first, so its peers rate it LEFT). Unlike Stop, it cannot be restarted.
- **Break and repair a server:** select it and use Leave gracefully, Stop or Restart in the inspector.
  Stop closes a server without telling its peers, so with gossip the others move it from ALIVE to SUSPECT
  and then DEAD. Leave announces a graceful departure and shows as LEFT. Restart brings a server back on the
  same ports. (Leave needs gossip.)

The same operations are available in the console if you prefer it: `send alpha hello`,
`test alpha 1000 100`, `server stop alpha-Server-002`.

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
