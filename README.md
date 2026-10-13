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
send alpha {"instruction":"math.add","parameters":{"a":10,"b":20}}
send beta {"instruction":"text.concat","parameters":{"a":"Hello ","b":"swarm"}}
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
The underlying server also runs its startup plugins, including the UDP address
announcement on port 9002. Server logs are visible in the same terminal.

The `send` command targets every server in the selected swarm. It generates a
different message UUID for each request and uses each server's expected ID to
validate replies. It prints each verified reply, for example:

```text
Swarm reply | messageId=<uuid> | serverId=alpha-Server-001 | connectionId=<id> | status=OK | result=30
```

## Commands

| Command | Behavior |
|---|---|
| `start <name> <servers>` | Start 1–100 instances concurrently in a named swarm |
| `list` | Show every swarm, server ID, and actual main/admin port |
| `status [name]` | Query the admin status of one or all swarms |
| `send <name> <text or JSON>` | Send to each server; plain text invokes `echo` |
| `test <name> [requests] [clients]` | Run a verified load test; defaults: 1,000 requests, concurrency 100 |
| `stop <name>` | Close every instance in one swarm |
| `stop-all` | Close every managed instance |
| `help` | Show commands |
| `quit` | Close all swarms and exit |

Command keywords are case-insensitive. Swarm names are case-sensitive, must begin
with a letter, and may contain ASCII letters, digits, dots, underscores, or hyphens.
Duplicate names are rejected. After stopping a swarm, you can reuse its name.
If any server fails during swarm startup, all servers opened by that attempt are
closed; previously running swarms remain available.

The load test sends `math.add` requests round-robin across the selected swarm,
using virtual-thread clients and a limit on requests in flight. Each request uses
a unique message UUID, validates the expected server ID, and checks the numeric
result. Request counts range from 1 to 100,000; concurrency ranges from 1 to 1,000.
Progress appears every 100 completions, followed by a summary:

```text
Swarm test PASSED | swarm=alpha | requests=1000 | passed=1000 | failed=0 | elapsedMs=<duration>
```

Failures are counted and the first ten details are printed. `send` stops and
reports an error if a server rejects a command or cannot be reached. This first
version is an interactive harness; test failure is reported in its output.
It does not supply a batch-mode exit code or performance benchmark guarantees.

## External Plugins

To make an external plugin available to all newly created servers, start the
harness with a plugin directory:

```powershell
mvn compile exec:java "-Dexec.args=--plugins-dir ../socket-server-threadpool/examples/multiply-plugin/target"
```

Build the sibling example first if necessary:

```powershell
mvn -f ../socket-server-threadpool/examples/multiply-plugin/pom.xml clean verify
```

Then enter:

```text
start plugins 2
send plugins {"instruction":"math.multiply","parameters":{"a":6,"b":7}}
quit
```

Each reply includes `result=42`. A supplied directory must exist; server startup
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

`quit`, end-of-input, and the JVM shutdown hook close managed swarms and wait for
accepted server work to finish. All instances share one JVM, so this version tests
socket behavior and instance lifecycle rather than process isolation. It manages
only instances it starts; it does not adopt servers discovered through UDP.
