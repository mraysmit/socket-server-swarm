# Swarm Test Suite Overview

## Overview

The suite has 52 test methods (65 cases with parameterized inputs) in five JUnit 5 classes under `dev.mars.swarm`. They start real servers on local ports, so they are integration tests rather than mocks.

- **SwarmManagerTest** covers the `SwarmManager` API: start, stop, status, send, test, list, validation, failure handling, and stopping or restarting one server.
- **SwarmConsoleTest** covers the `SwarmMain` console (including the `server` command), plugin-folder argument parsing, and JVM shutdown-hook cleanup.
- **UiServerTest** covers the HTTP endpoint: the snapshot, the page, Host checks, the action endpoints and their cross-site protections, the activity endpoint, and the UI command-line options.
- **SocketActivityTest** covers the recorder that turns the servers' log lines into per-server socket activity.
- **SwarmGossipTest** covers the generated gossip config and, with the real gossip plugin, convergence, failure detection, graceful leave, restart, swarm isolation, echo traffic, and the console path. The plugin tests skip themselves if the plugin JAR is not built.
- **junit-platform.properties** sets a 60 s default timeout and runs timed tests on a separate thread, so a hung test fails instead of stalling the build.

## Test inventory

### SwarmManagerTest (12 methods, 45 s timeout)

| Test | What it verifies |
| --- | --- |
| countsMixedHealthyAndUnreachableTargetsWithoutStoppingHealthyServers | With one of 3 servers closed, 12 requests give 8 passed and 4 failed; the 2 healthy servers stay alive; ports are freed on stop. |
| handlesSmallAndNonRoundRequestCountsWithFinalProgress (1, 99, 101) | All requests pass; progress is reported in batches of 100 and always ends on the final count. |
| forwardsAllRuntimeFoldersToEveryServer | Every server receives all plugin folders; later changes to the caller's list do not leak in. |
| managesIndependentSwarmsAndCorrelatesEveryReply | Two swarms run side by side with unique ports and IDs; echo replies match their server and have unique message IDs; stopping one leaves the other alive; `list()` is read-only. |
| verifiesOneThousandRequestsAcrossThreeServers | 1000 requests over 3 servers all pass, with progress every 100. |
| failedStartupRollsBackEveryNewInstanceAndPreservesOtherSwarms | If the 2nd of 3 servers fails to start, the 2 started are closed, the swarm is not registered, and other swarms are untouched. |
| reportsUnreachableServersAndLoadFailures | With the only server closed, status shows not alive; all 12 requests fail and only 10 failures are recorded. |
| missingPluginDirectoryDoesNotRegisterSwarm | A missing plugin folder throws `IOException` and registers nothing. |
| rejectsDuplicateAndUnknownNamesAndAllowsReuseAfterStop | Duplicate or unknown names throw `IllegalArgumentException`; a name can be reused after stop. |
| rejectsInvalidSwarmNames (null, empty, blank, "two words", "1first", "bad\|name") | Invalid names are rejected and nothing is registered. |
| rejectsInvalidServerCounts (-1, 0, 101) | Server counts outside 1 to 100 are rejected. |
| validatesLoadSettingsAndCloseIsIdempotent | Requests outside 1 to 100000 and clients outside 1 to 1000 are rejected; `close()` can be called twice and releases ports; starting after close throws `IllegalStateException`. |

### SwarmConsoleTest (5 methods, 30 s timeout)

| Test | What it verifies |
| --- | --- |
| virtualShutdownHookClosesServersDuringJvmExit | Launches a separate JVM that registers the shutdown hook and calls `System.exit(0)`. Checks the hook is a virtual thread, the JVM exits within 10 s with code 0, listener cleanup is logged, no network announcement is sent, and both ports are free afterwards. |
| acceptsRepeatedRuntimePluginFoldersAndRejectsMissingValues | `--plugins-dir` can repeat and keeps paths with spaces; a missing value, blank value, unknown flag or doubled flag throws `IllegalArgumentException`. |
| consoleManagesTwoSwarmsSendsCommandsAndShowsTestProgress | A scripted console session runs start, list, status, send, test, stop and stop-all on two swarms. Checks reply format, unique message IDs, `completed=20/20` progress, the PASSED summary, and that beta stays healthy after alpha stops. |
| invalidCommandsReportErrorsAndConsoleContinues | Bad input (non-numeric count, missing arguments, unknown swarm, out-of-range requests, unknown command, `quit extra`) prints an error and the console keeps running. |
| eofReturnsAndOwnerClosesSwarms | End of input returns from the console without stopping swarms; closing the manager does. |

### Added later: server operations (in SwarmManagerTest and SwarmConsoleTest)

| Test | What it verifies |
| --- | --- |
| stopsAndRestartsOneServerOnItsSamePortsWithoutTouchingItsPeers | A stopped server stays listed with its ports and shows as not alive while its peers stay alive. Restart brings it back on the same ports. Restarting a running server replaces it. |
| serverOperationsRejectUnknownServersAndLeaveNeedsGossip | Malformed or unknown server IDs throw `IllegalArgumentException`; `leave` without gossip throws `IllegalStateException`; operations after close fail. |
| failedRestartKeepsTheServerListedAndReportsWhy | A restart that fails reports the reason, the server stays listed, and a later restart works. |
| serverCommandsStopAndRestartOneServerAndReportMisuse (console) | `server stop`, `server restart`, and error messages for unknown servers, unknown actions and missing arguments. |

### SocketActivityTest (6 methods)

| Test | What it verifies |
| --- | --- |
| groupsOneConnectionsLinesIntoConnectRequestAndReply | A connect, an instruction and a reply line become three events with the right connection ID, client, message ID and status, and the counters. |
| countsErrorsAbortsAndInstructionsAndIgnoresOtherLines | Error replies, aborted requests (the stderr format) and per-instruction counts; admin lines, server-ready lines, blank and null input are ignored. |
| keepsServersApartAndOnlyTheNewestEventsPerServer | Servers are tracked separately, the buffer keeps the newest 300 events, and counters keep counting past it. |
| viewReturnsOnlyEventsAfterAGivenSequenceAndHonoursTheLimit | The `after` sequence and the limit. |
| installCopiesConsoleOutputToTheRecorderAndRestoresTheStreams | Console output still reaches the original stream, lines split across writes are joined, and closing restores System.out and System.err. |
| recordsRealServerTrafficFromTheirLogLines | Real servers' echo requests are recorded with counters, message IDs and client addresses. |

### UiServerTest (12 methods)

`fullMembersEndpointRejectsUnknownServersAndServersWithoutGossip` checks that the complete-member-list endpoint gives 404 for an unknown server and 502 for one without the plugin, and that a POST to it is refused. The two activity endpoint tests (`activityEndpointServesAServersSocketActivityAndOnlyNewEvents` and `activityEndpointSaysSoWhenNothingIsRecordedOrAServerHasBeenQuiet`) check counters, events, the `after` parameter, unknown servers (404), the "not recorded" answer, and that GET on an action path still gives 405. The list below was written before them.

| Test | What it verifies |
| --- | --- |
| snapshotReportsEverySwarmAndServer | The snapshot lists every swarm and server with real ports, `alive`, and null gossip when the plugin is absent. |
| snapshotShowsAClosedServerAsNotAliveAndEmptyWhenNothingRuns | An empty snapshot with no swarms, and a closed server reported as not alive. |
| servesThePageAndRejectsOtherPathsAndMethods | The page is served at `/` and `/index.html`; other paths give 404 and POST to the snapshot gives 405. |
| refusesRequestsWhoseHostIsNotLoopback (4 hosts) | Requests with a foreign `Host` get 403; loopback names are accepted. |
| actionsStopAndRestartAServerAndReportErrors | `POST /api/servers/<id>/stop` and `/restart` work; unknown server gives 404; `leave` without gossip gives 409; unknown actions give 404; GET gives 405. |
| actionsRefuseRequestsThatDoNotComeFromThePageThisServerServed | An action is refused (403) with no `Origin`, a foreign `Origin`, another port, or no `X-Swarm-UI` header, and the server is left running. |
| closeReleasesThePort, aBusyPortIsReportedToTheCaller | The port is freed on close; a busy port throws `IOException`. |
| parsesUiOptionsWithTheUiOnByDefault | `--ui-port` and `--no-ui` parsing, including invalid ports. |

### SwarmGossipTest (13 methods)

| Test | What it verifies |
| --- | --- |
| generatedConfigGivesEachServerItsSwarmAndSeedsPointAtTheFirstServer | Each server gets its swarm ID, servers 2 and up are seeded with the first server's real port, entries are removed on stop, and the file and property are removed on close. |
| failedStartupForgetsTheSwarmsConfigAndNoGossipMeansNoProperty | A failed start leaves no config behind; without gossip the property is never set. |
| settingsRejectHarnessOwnedAndBlankValues, parsesGossipOptions | `GossipSettings` and the `--gossip` and `--gossip-fast` options. |
| serversConvergeAndReportTheirViewsThroughTheSnapshot | Four servers each see all members ALIVE, none is isolated, and the snapshot carries the plugin's status. |
| swarmsStaySeparateAndUseTheirOwnSwarmId | Each swarm uses its own swarm ID and lists only its own members. |
| closedServerIsSuspectedByThePeersWhichStayHealthy | A closed server is rated SUSPECT or DEAD by its peers, which keep rating each other ALIVE. |
| leavingServerIsMarkedLeftByThePeersQuickly | After `leave`, the peers record LEFT and the departure completes. |
| restartedServerGetsANewBootIdAndRejoinsTheSwarm | A restarted server has a new boot ID, rejoins, and its peers rate the new boot ID ALIVE. |
| echoTrafficStillWorksWhileTheServersGossip | 300 echo requests succeed while the servers converge. |
| consoleStartsAGossipSwarmAndSendsAGracefulLeave | A console session starts a gossip swarm, sends a graceful leave to one server, and cleans up its config. |
| aSwarmLargerThanOneMembersPageIsReadWholeByFollowingTheCursor | With 40 gossip servers, one members page of 32 is cut short, and `SwarmSnapshot.fullMembers` follows the cursor to return all 40 members exactly once, each ALIVE. |
| swarmSeededAtAnotherSwarmRejectsItAndKeepsItsOwnMembers | A server seeded at another swarm's server has its exchange turned away, and neither swarm lists the other's members. |

## Running the tests

The project is built with Maven, compiles for Java 27, and uses JUnit Jupiter.

```
mvn test
mvn test -Dtest=SwarmManagerTest
mvn test -Dtest=SwarmConsoleTest
```

The tests bind free local ports, so no external services are needed. The shutdown-hook test starts a second JVM from the test classpath and looks for `java.exe` or `java` under `java.home`.

## Coverage notes

- Strong on lifecycle, input validation, rollback on failed startup, port release, and partial failure under load.
- The only load test is 1000 requests across 3 servers on one machine; there are no concurrency stress tests of the manager itself.
- Protocol behavior beyond the echo instruction, and plugin loading with real plugins, is not tested here (only that plugin folders are forwarded or missing).
- Timeouts and `SEPARATE_THREAD` mode in `junit-platform.properties` prevent hangs from blocking the build.
