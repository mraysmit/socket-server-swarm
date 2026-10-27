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
| `put id, key, value`, `get id, key`, `delete id, key`, `keys id`, `kvStatus id` | Key-value data on one server. |
| `call id, instruction, parameters` | Any instruction on one server. |
| `load name, requests, concurrency` | A verified echo load test across the swarm. |
| `alive name`, `servers name`, `randomServer name`, `snapshot()` | The members up, every server, one at random, everything. |
| `awaitAlive name, n`, `awaitInSync name`, `waitFor what, ms { ... }` | Wait for gossip to agree, for the stores to match, or for any condition. |
| `check condition, 'what'`, `log 'text'`, `pause ms` | Record a pass or fail, print, wait. |
| `experiment 'title', [steady: {...}, method: {...}, rollback: {...}]` | A chaos experiment: steady state before and after, rollback always. |

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

The waits in the chaos scripts suit the harness's `--gossip-fast` timings.
