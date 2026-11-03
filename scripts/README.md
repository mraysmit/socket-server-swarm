# Swarm scripts

Groovy scripts that act on a swarm while the harness is running: add and remove servers, kill, freeze and restart them,
send key-value data, and run chaos experiments with a pass or fail result.

## Running one

Start the harness first, for example with gossip and replication:

```powershell
mvn compile exec:java "-Dexec.args=--gossip-fast --kv-replicate --plugins-dir ../socket-server-threadpool/plugins/gossip-plugin/target --plugins-dir ../socket-server-threadpool/plugins/kv-plugin/target"
```

and `start kv 5` in its console (or in the page). Then, from another terminal:

```powershell
.\scripts\swarm.ps1 scripts\kill-and-recover.groovy kv
```

```sh
scripts/swarm.sh scripts/chaos/random-kills.groovy kv 5
```

The wrapper runs `groovy -cp scripts/lib <script> <args>`. It needs Groovy on the path and JDK 27. A script finds the
harness at `SWARM_HARNESS` (default `http://localhost:8088`). Its exit status is 0 when every check passed, 1 when a
check failed, and 2 when the script itself failed.

## How they work

Servers are started, stopped, frozen and restarted through the harness, which owns them, using the same control
endpoints the page uses. Everything else goes straight to a server over its own protocol, a JSON line per request on a
kept-open TCP connection. Scripts run outside the harness, so a failing script cannot take the swarm down, and the page
shows what a script does as it does it.

## Writing one

Start the file with `@groovy.transform.BaseScript SwarmScript swarm`; the verbs of
[lib/SwarmScript.groovy](lib/SwarmScript.groovy) are then available directly.

| Verbs | What they do |
| --- | --- |
| `start name, n`, `add name, n`, `stopSwarm name` | Start a swarm, add servers to it, stop it. |
| `kill id`, `restart id` | Stop a server without telling its peers, as a crash would; start it again on the same ports. |
| `remove id`, `leave id` | Take a server away gracefully; publish a gossip departure but keep it running. |
| `freeze id`, `thaw id`, `frozenFor id, ms { ... }` | Pause a server as a debugger or long GC pause would: it keeps its connections but does nothing. |
| `partition sideA, sideB, ...`, `isolate id` | Cut servers off from one another, both ways; each still answers scripts and the harness. |
| `delay ids, ms`, `loss ids, percent`, `heal()`, `network()` | Slow down or lose requests to and from servers; undo every network rule; see the rules in force. |
| `put id, key, value`, `get id, key`, `delete id, key`, `keys id`, `kvStatus id` | Key-value data on one server. |
| `call id, instruction, parameters` | Any instruction on one server. |
| `load name, requests, concurrency` | A verified echo load test across the swarm. |
| `alive name`, `servers name`, `randomServer name`, `snapshot()` | The members up, every server, one at random, everything. |
| `assessments id`, `bootId id`, `rating id, memberId, bootId` | One server's gossip view: how many members it rates ALIVE, SUSPECT, DEAD and so on; the boot ID a server runs under now; how one server rates one boot of another. |
| `awaitAlive name, n`, `awaitInSync name`, `waitFor what, ms { ... }` | Wait for gossip to agree, for the stores to match, or for any condition. |
| `check condition, 'what'`, `log 'text'`, `pause ms` | Record a pass or fail, print, wait. |
| `startRaft name, n`, `propose id, command`, `proposeToLeader name, command`, `raftStatus id`, `raftLog id` | A Raft swarm, a command to commit on one server or on whoever leads, and what a server knows and holds. |
| `leader name`, `awaitLeader name`, `awaitCommitted name, index` | Who leads, and waiting for a leader or for every server to commit. |
| `commitNotes name, label, n`, `checkHeldEverywhere name, committed`, `checkLogsAgree name`, `checkOneLeaderPerTerm name` | Commit a few commands and remember where, and check Raft's promises. |
| `kvPut key, value`, `kvGet key`, `raftRead id, command`, `readThrough id, command` | kv commands for Raft, and a read on one server, or through it to whoever it says leads. |
| `awaitApplied name, index`, `checkCheckpointsAgree name` | Wait for every server to apply this far, and check that each has applied the same things with the same kinds of outcome. |
| `startRaft name, n, 'kv'`, `restartWithAllPlugins id` | A Raft swarm without that plugin, and giving it to one server. |
| `clientsDuring label, clients, keys, write, read { ... }`, `raftWrites ids`, `raftReads ids`, `checkLinearizable history, 'what'` | Clients reading and writing while something happens, and the history checker's verdict on what they saw. |
| `mark 'text'` | Note a step on the page's timeline, so a replay shows what was done when. |
| `experiment 'title', [steady: {...}, method: {...}, rollback: {...}]` | A chaos experiment: steady state before and after, rollback always. It marks its steps on the timeline itself. |

`alive` means up and still a member: a frozen server, or one that has left gossip, is not counted.

## The scripts

| Script | What it shows |
| --- | --- |
| [kv-roundtrip.groovy](kv-roundtrip.groovy) | A pair written on one server is read on every other, and its deletion reaches them all. |
| [membership.groovy](membership.groovy) | Two servers join and catch up; one departs gracefully, one without warning. |
| [kill-and-recover.groovy](kill-and-recover.groovy) | A killed server gets the writes it missed when it restarts. |
| [chaos/random-kills.groovy](chaos/random-kills.groovy) | Random kills and restarts under writes lose no acknowledged write. |
| [chaos/rolling-restart.groovy](chaos/rolling-restart.groovy) | Restarting every server in turn, as a deployment would, loses nothing. |
| [chaos/churn.groovy](chaos/churn.groovy) | Servers join and go continuously while clients write. |
| [chaos/freeze.groovy](chaos/freeze.groovy) | A server frozen past the suspect and then the dead timeout is noticed, and recovers when thawed. |
| [chaos/partition.groovy](chaos/partition.groovy) | Split in two, each side sees only itself and keeps its writes; healed, it converges and a key written on both sides holds the later write. |
| [chaos/swarm-of-ten.groovy](chaos/swarm-of-ten.groovy) | Starts a swarm of ten from nothing, then a third crashes at once, it splits four against six, and gray failures (slowed, lossy and frozen servers) strike together; no acknowledged write is lost. |
| [chaos/slow-network.groovy](chaos/slow-network.groovy) | Delay everywhere and heavy loss at one server: writes are still taken and the swarm still converges. |
| [chaos/raft-leader-loss.groovy](chaos/raft-leader-loss.groovy) | Stopping a Raft leader elects another within a target, loses nothing committed, and the old one rejoins as a follower. |
| [chaos/raft-frozen-leader.groovy](chaos/raft-frozen-leader.groovy) | A frozen leader is replaced. Thawed, it wakes believing it leads, steps down, and catches up. |
| [chaos/raft-partition.groovy](chaos/raft-partition.groovy) | With the leader on the smaller side of a split, that side commits nothing and the larger side carries on. After healing they agree, and what the old leader could not commit is gone. |
| [chaos/raft-restart-all.groovy](chaos/raft-restart-all.groovy) | Every server stopped and restarted keeps all that was committed, and elects a leader with no bootstrap. |
| [chaos/raft-lossy.groovy](chaos/raft-lossy.groovy) | Delay everywhere and heavy loss at the leader slow commits without breaking agreement. |
| [chaos/raft-isolated-rejoin.groovy](chaos/raft-isolated-rejoin.groovy) | A follower cut off for a minute never enters a new term, because it never wins a pre-vote, and when it returns the same leader carries on in the same term. |
| [chaos/raft-cut-off-leader.groovy](chaos/raft-cut-off-leader.groovy) | A leader cut off from everyone steps down by itself within a bound, so a write or read sent to it is refused at once, not left to time out. The others elect a leader, and it follows when it returns. |
| [chaos/raft-change-voters.groovy](chaos/raft-change-voters.groovy) | Changes the voters while things go wrong: a voter that crashed is removed and a fresh server added in its place, the larger side of a split adds a server and removes one cut off on the smaller side, which commits nothing meanwhile, and the leader is removed while clients write. After each, one leader per term, the same committed log everywhere, and every server names the servers listed as its voters. |
| [chaos/raft-snapshot.groovy](chaos/raft-snapshot.groovy) | Logs compacted into snapshots, with the real kv: a follower down while more than the snapshot interval committed is sent a snapshot, every server restarted rebuilds kv from its snapshot and the log after it, and a fresh server added is caught up by one. After each, every server holds the same values and reports the same checkpoint. |
| [raft-kv.groovy](raft-kv.groovy) | The demo of kv on Raft: a write committed and applied on every server, read back through any of them. A typo and a write sent as a read are refused. It leaves its swarm running to look at. |
| [chaos/raft-kv-partition.groovy](chaos/raft-kv-partition.groovy) | With clients reading and writing throughout a split, the smaller side refuses both, no acknowledged write is lost, and the history checker explains all that clients saw. |
| [chaos/raft-kv-restart-all.groovy](chaos/raft-kv-restart-all.groovy) | Every server stopped and restarted gets all its kv data back, though kv itself keeps nothing. |
| [chaos/raft-kv-late.groovy](chaos/raft-kv-late.groovy) | kv commands commit with kv not loaded. Restarting servers one at a time with it, each catches up, and they all agree. |
| [chaos/history-checker.groovy](chaos/history-checker.groovy) | That the history checker works: hand-written histories whose answers are known, and violations found in last-writer-wins replication through a split. |
| [compare-split.groovy](compare-split.groovy) | One split on a last-writer-wins swarm and a Raft swarm at once, with clients on both and the same two writes to one key on each. Both sides of the first take them and one is lost; the smaller side of the second refuses, and nothing acknowledged is lost. It reports what each did, and leaves both swarms running to look at. Open the page while it runs. |

The Raft scripts start their own swarms and stop them at the end, except `raft-kv` and `compare-split` unless told to. They need the Raft plugin in the harness's `--plugins-dir`, and the `raft-kv` scripts need kv too. `chaos/history-checker` needs gossip, kv and `--kv-replicate`, and `compare-split` needs all of those and Raft.

`lib/HistoryChecker.groovy` decides whether a history of client operations could have come from one copy of the data. It checks each key by itself, each write must write a value of its own, and it allows a write of unknown outcome to have taken effect at any time after it was made, or never. If a key defeats its search, it reports that key as undecided, which fails the check and is not taken as a pass.

Network faults apply to the requests servers make of one another (gossip exchanges, key-value pushes and comparisons),
which all go through the host's kept-open client. The harness decides each one from the rules in force; a lost request
times out at its sender, as behind a real partition. Requests from scripts, the page and the harness are never faulted.

The waits in the chaos scripts suit the harness's `--gossip-fast` timings.
