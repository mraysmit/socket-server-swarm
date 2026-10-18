# Swarm Gossip Test Plan

## Purpose

Test that server instances started by the swarm harness can form a swarm using the gossip plugin. The plugin lives in the sibling repository `socket-server-threadpool` (`plugins/gossip-plugin`, design in `docs/GOSSIP_PLUGIN.md`). This repository has no gossip code. The plan covers what to add here so the plugin is exercised through `SwarmManager` and the console, using the packaged plugin JAR.

The plugin's own repository already has thorough protocol, wire, lifecycle and real-socket tests (for example `GossipSwarmTest`, `GossipPluginIT`, `GossipPropagationTest`). This plan does not repeat them.

## What the harness already provides

- `SwarmManager` starts N real `ServerInstance`s in one JVM, named `<swarm>-Server-001` and so on.
- `--plugins-dir` is forwarded to every server, so pointing it at `socket-server-threadpool/plugins/gossip-plugin/target` loads the plugin.
- The gossip plugin keeps its state per instance, so several servers in one JVM are supported.
- `send` reaches every server in a swarm. `{"instruction":"gossip","parameters":{"operation":"members"}}` returns each node's own view, and `operation":"status"` returns its worker and departure state.
- Surefire already sets `dev.mars.announce=false`, so test servers do not announce on the LAN.

## The main obstacle: seed ports

Gossip needs `-Dgossip.config=<file>` with `swarm.id`, `advertise.host` and `seeds=ServerId@host:port`. The harness starts servers on port 0, so seed ports are unknown in advance.

Two options, both needing a small harness change:

| Option | How it works | Trade-off |
| --- | --- | --- |
| Write the config between starts | Start `Server-001` with no seeds, write its real port into the config file as a seed, then start the rest. The plugin re-reads the file at each server start. | No port reservation needed; startup becomes sequential. |
| Reserve ports first | `SwarmManager` picks free ports up front and passes them as explicit ports, so the seed list is known before any server starts. | Small race between reserving and binding a port. |

Per-server settings can use the plugin's `server.<serverId>.<key>` override form, for example `server.gossip-Server-001.advertise.host`. Use `advertise.host=127.0.0.1` for local runs.

Recommended: write the config between starts. The existing `ServerFactory` hook that the current tests use for fault injection is the natural place to do it.

## Test configuration

- Use short timings so tests run in seconds: `round.millis` about 100 to 200, `heartbeat.millis` about 500, `suspect.after.millis` and `dead.after.millis` a few seconds (keeping `heartbeat < suspect < dead`).
- Assert with polling and a bounded deadline, never fixed sleeps. The plugin docs note that live convergence time is variable.
- Keep `dev.mars.announce=false`.
- Build the plugin first: `mvn -f plugins/gossip-plugin/pom.xml clean verify`.
- Give the new test class its own timeout, longer than the current 45 s if needed.

## Planned tests

Proposed class: `SwarmGossipTest`.

| # | Test | Steps | Pass condition |
| --- | --- | --- | --- |
| 1 | Convergence | Start 3 to 5 servers with gossip, seeding only the first. Poll `members` on every server. | Every server lists every node as `ALIVE`, and `isolated` is false, within the deadline. |
| 2 | Failure detection | Converge, then close one instance. | The others mark it `SUSPECT`, then `DEAD`. The remaining nodes stay `ALIVE`. |
| 3 | Graceful departure | Send `leave` to one node, then poll its `status`. | `departure.state` becomes `COMPLETED`; the others show that node as `LEFT` well before the dead timeout. |
| 4 | Restart | Stop a node and start it again with the same server ID. | The new node has a new `bootId` and becomes reachable; the old `bootId` ages out. |
| 5 | Swarm isolation | Run two swarms with different `swarm.id` values, each seeded at the other. | Neither swarm admits the other's members. |
| 6 | Echo under gossip | Run the existing `test` command with 1000 requests while gossip runs. | All requests pass and every server stays alive. |
| 7 | Console path | Run a scripted console session that starts a gossip swarm, sends `members` and `leave`, and quits. | Output shows all members; no `Error:` lines; ports released after quit. |
| 8 | Partition (optional) | Place a TCP relay between nodes, drop traffic, then restore it. | Views converge again after reconnection. Needs a relay fixture; the plugin repository already has one, so this may stay there. |

## Required harness changes

1. A way to give `SwarmManager` (and the `start` console command) a gossip config: a config file path, or a few settings the harness writes itself.
2. Sequential start with seed discovery, or up-front port reservation (see the options above).
3. A helper that sends the `members` and `status` gossip operations and parses the reply, so tests can poll for convergence.
4. Release of the generated config file on `stop` and `close`.

## Status

The harness changes are done, with one difference from the list above: gossip is switched on for the whole harness (`--gossip` or `--gossip-fast`), not per `start` command. The new tests are in `SwarmGossipTest` and skip themselves if the plugin JAR has not been built.

| # | Test | Status |
| --- | --- | --- |
| 1 | Convergence | Done (`serversConvergeAndReportTheirViewsThroughTheSnapshot`, also checks the snapshot) |
| 2 | Failure detection | Done (`closedServerIsSuspectedByThePeersWhichStayHealthy`, checks SUSPECT or DEAD) |
| 3 | Graceful departure | Done (`leavingServerIsMarkedLeftByThePeersQuickly`) |
| 4 | Restart | Done (`restartedServerGetsANewBootIdAndRejoinsTheSwarm`: same ports, new boot ID, peers rate the new boot ALIVE) |
| 5 | Swarm isolation | Done (`swarmsStaySeparateAndUseTheirOwnSwarmId`, and `swarmSeededAtAnotherSwarmRejectsItAndKeepsItsOwnMembers`, which seeds a beta server at an alpha server and checks that alpha turns the exchange away and neither swarm lists the other) |
| 6 | Echo under gossip | Done (`echoTrafficStillWorksWhileTheServersGossip`, 300 requests while the servers converge) |
| 7 | Console path | Done (`consoleStartsAGossipSwarmAndSendsAGracefulLeave`) |
| 8 | Partition | Not done, on purpose. It needs a TCP relay between the servers, which the plugin's own repository already has and tests thoroughly. Building a second one here would duplicate it. |

Also covered without the plugin: the generated config (swarm IDs, seeds, cleanup on stop, failure and close), the command-line options, stopping and restarting a single server, the `server` console commands, and the HTTP action endpoints with their cross-site protections.

## Risks and open questions

- Convergence time depends on timing settings and machine load; the plugin's own six-node hostname test has missed a 20-second deadline once. Use generous deadlines and avoid asserting exact times.
- `gossip.config` is a JVM system property, so it is shared by every server in the JVM. Per-swarm configuration must therefore use the `server.<serverId>.<key>` overrides, or one config file with distinct entries per swarm.
- Starting two swarms with different `swarm.id` values in one JVM depends on the per-instance override form working as the plugin docs describe; confirm this with test 5 early.
- The tests depend on a built plugin JAR in a sibling repository. Decide whether a missing JAR skips the tests or fails them.

## Suggested order

1. Add the harness support for a gossip config and sequential seeded start.
2. Implement tests 1 and 3 (convergence and `leave`) to prove the setup.
3. Add tests 2, 4, 5 and 6.
4. Add the console test (7).
5. Decide on test 8 after the others are stable.
