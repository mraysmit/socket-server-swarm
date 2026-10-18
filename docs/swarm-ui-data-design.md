# Swarm UI Data Design

## Purpose

The mockup in [ui/swarm-observatory.html](../ui/swarm-observatory.html) shows a live view of server swarms, but it runs a simulation in the browser. This document describes how the page can get real data from the swarm harness instead.

The proposal is based on reading `SwarmManager` and `SwarmMain`. It has not been built or tested. The gossip plugin lives in the sibling repository `socket-server-threadpool`; its behavior here is taken from `docs/GOSSIP_PLUGIN.md` there. I did not check what the server's HTTP handling on the main port returns.

## What the UI needs and where it comes from

| UI element | Source today |
| --- | --- |
| Swarms, servers, ports | `SwarmManager.list()` |
| Running or stopped | `SwarmManager.status()`, which queries the admin port |
| Each server's gossip view (member table, ratings, heartbeats) | The `gossip` `members` operation, sent to each server |
| Boot ID, rounds, isolated flag, worker and departure state | The `gossip` `status` operation |
| Event log | Nothing yet. The harness would need to record start, stop and leave actions. |

Two kinds of information must stay separate:

- **Ground truth** comes from the harness JVM, which knows which servers are actually running.
- **Opinion** comes from each server's gossip view of the others.

The UI draws ground truth as the node outline and opinion as the badge from the selected server's point of view. The API should keep the two apart so that disagreement between them is visible.

## Transport

A browser cannot speak the servers' raw TCP protocol, so the harness needs a small HTTP layer.

- Use the JDK's built-in `com.sun.net.httpserver`. It adds no dependency, which suits the current pom.
- Bind to localhost only.
- Serve `ui/swarm-observatory.html` from the same server. Page and data then share an origin, so no CORS handling is needed.
- Start with polling: `GET /api/snapshot`, requested by the page every second or so. It is simple, easy to test, and survives a page refresh.
- Add Server-Sent Events (`/api/events`) later for the event log. WebSockets are not needed.
- Start the endpoint when the harness starts, on a fixed port (see Decisions). `quit`, end-of-input and the JVM shutdown hook should close it along with the swarms.

## Proposed snapshot shape

This is a draft for discussion, not a fixed contract.

```json
{
  "takenAtMillis": 1760000000000,
  "swarms": [
    {
      "name": "alpha",
      "servers": [
        {
          "serverId": "alpha-Server-001",
          "mainPort": 50102,
          "adminPort": 50103,
          "alive": true,
          "gossip": {
            "bootId": "0f3a9c1e-...",
            "workerState": "RUNNING",
            "departure": "NOT_REQUESTED",
            "isolated": false,
            "rounds": 42,
            "heartbeat": 42,
            "members": [
              { "serverId": "alpha-Server-002", "bootId": "...", "state": "ALIVE", "heartbeat": 41, "advanceAgeMillis": 800 }
            ]
          }
        }
      ]
    }
  ]
}
```

`gossip` is null for a server that is down, not answering, or running without the plugin. As built, `gossip` holds the plugin's own `status` and `members` replies unchanged, as `{"status": ..., "members": ...}`, because their exact field names are not confirmed. The `bootId`, `workerState` and similar fields above are therefore a target shape that the page will have to map from the plugin's replies. Each server is probed in parallel with a 6-second limit; a server that does not answer in time is reported as not alive.

## Known gaps

1. **Gossip is not wired in yet.** The seed-port work in [swarm-tests-plan.md](swarm-tests-plan.md) must come first. Without it, `members` returns nothing useful.
2. **Exchange dots cannot be exact.** The plugin exposes counters (exchanges received, failed outbound), not individual exchanges. The UI could infer dots from counter changes, but they would be approximate. Exact dots need an event hook in the gossip plugin.
3. **Per-server actions do not exist.** `SwarmManager` can only stop a whole swarm. Stopping, leaving or restarting a single server needs new harness methods. Partition needs a TCP relay between nodes. The first version should be read-only.
4. **Polling cost.** Querying every server each second is fine for a few servers. For swarms up to 100 servers, query in parallel with a short timeout and cache the last snapshot.
5. **Page changes.** The mockup assumes the simulator owns time and state. Switching to real data means replacing its model with the snapshot, and deciding whether the simulator stays as a fallback.

## Build order

1. Wire the gossip config into the harness (see the test plan). Not started.
2. Add a read-only `/api/snapshot` and serve the page from the same server. **Done**: `UiServer`, `SwarmSnapshot`, and the `--ui-port` and `--no-ui` options, covered by `UiServerTest`. The page served is still the simulator, so step 3 is next. Until step 1, `gossip` is always null.
3. Make the page use the endpoint when it exists, and keep the simulator as a fallback.
4. Add a harness event log and the SSE feed.
5. Add per-server actions last.

## Tests

- Unit-test snapshot building with a `SwarmManager` that uses the existing `ServerFactory` fault-injection hook.
- Test the endpoint over a real socket: start a swarm, request the snapshot, close a server, and check that `alive` turns false.
- Check that closing the manager also closes the HTTP listener and frees its port, as the current lifecycle tests do for server ports.
- Once gossip is wired in, assert on snapshot contents in `SwarmGossipTest` (convergence, `leave`).

## Decisions

These start the design; revisit them if they cause trouble.

- **On by default.** The endpoint starts with the harness, with no extra console command. Add an off switch (for example `--no-ui`) so tests and headless runs can avoid binding the port.
- **Fixed port.** The URL is predictable, so it can be bookmarked. The port number is not yet chosen; this document assumes 8088. An override, `--ui-port`, is available. If the port is taken, the harness prints a message naming `--ui-port` and `--no-ui` and carries on without the UI, so a second harness instance still starts. Tests build `UiServer` directly on port 0 and never use the fixed port.
- **The event log does not survive a refresh.** The page starts with an empty log after a reload and shows only events that happen from then on. The server keeps no event history, which removes the need for an event buffer and replay.

- **A reload still shows current state.** The first snapshot carries every server's current state, including servers that are already stopped. Only the log is empty, so the graph looks right straight away.

## Open questions

- Which port should be the default?
