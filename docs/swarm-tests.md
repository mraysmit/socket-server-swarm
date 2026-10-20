# Swarm Test Suite Overview

## Overview

The suite has 90 test methods (102 cases with parameterized inputs) in eight JUnit 5 classes under `dev.mars.swarm`. They start real servers on local ports, so they are integration tests rather than mocks.

- **SwarmManagerTest** covers the `SwarmManager` API: start, stop, status, send, test, list, validation, failure handling, and stopping or restarting one server.
- **SwarmConsoleTest** covers the `SwarmMain` console (including the `server` command), plugin-folder argument parsing, and JVM shutdown-hook cleanup.
- **UiServerTest** covers the HTTP endpoint: the snapshot, the page, Host checks, the action endpoints and their cross-site protections, the activity endpoint, and the UI command-line options.
- **SocketActivityTest** covers the recorder that turns the servers' log lines into per-server socket activity.
- **ConnectionPoolTest** covers the harness's pool of kept-open connections to the servers.
- **SwarmGossipTest** covers the generated gossip config and, with the real gossip plugin, convergence, failure detection, graceful leave, restart, swarm isolation, echo traffic, and the console path. The plugin tests skip themselves if the plugin JAR is not built.
- **junit-platform.properties** sets a 60 s default timeout and runs timed tests on a separate thread, so a hung test fails instead of stalling the build.

## Test inventory

### SwarmManagerTest (20 methods, 45 s timeout; the server-operation tests are listed under "Server operations" below)

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

### SwarmConsoleTest (8 methods, 30 s timeout)

| Test | What it verifies |
| --- | --- |
| virtualShutdownHookClosesServersDuringJvmExit | Launches a separate JVM that registers the shutdown hook and calls `System.exit(0)`. Checks the hook is a virtual thread, the JVM exits within 10 s with code 0, listener cleanup is logged, no network announcement is sent, and both ports are free afterwards. |
| acceptsRepeatedRuntimePluginFoldersAndRejectsMissingValues | `--plugins-dir` can repeat and keeps paths with spaces; a missing value, blank value, unknown flag or doubled flag throws `IllegalArgumentException`. |
| consoleManagesTwoSwarmsSendsCommandsAndShowsTestProgress | A scripted console session runs start, list, status, send, test, stop and stop-all on two swarms. Checks reply format, unique message IDs, `completed=20/20` progress, the PASSED summary, and that beta stays healthy after alpha stops. |
| invalidCommandsReportErrorsAndConsoleContinues | Bad input (non-numeric count, missing arguments, unknown swarm, out-of-range requests, unknown command, `quit extra`) prints an error and the console keeps running. |
| eofReturnsAndOwnerClosesSwarms | End of input returns from the console without stopping swarms; closing the manager does. |

### Server operations (in SwarmManagerTest and SwarmConsoleTest)

| Test | What it verifies |
| --- | --- |
| stopsAndRestartsOneServerOnItsSamePortsWithoutTouchingItsPeers | A stopped server stays listed with its ports and shows as not alive while its peers stay alive. Restart brings it back on the same ports. Restarting a running server replaces it. |
| serverOperationsRejectUnknownServersAndLeaveNeedsGossip | Malformed or unknown server IDs throw `IllegalArgumentException`; `leave` without gossip throws `IllegalStateException`; operations after close fail. |
| failedRestartKeepsTheServerListedAndReportsWhy | A restart that fails reports the reason, the server stays listed, and a later restart works. |
| theKeyValuePluginAnswersCommandsToOneServerAndEachServerKeepsItsOwnPairs | With the sibling kv plugin loaded (skipped when it is not built), `command` puts, gets, lists and deletes pairs on one server, including an object value; the other server does not have them; a put without a value and an unknown operation return `INVALID_PARAMETERS`; and a server without the plugin answers `UNKNOWN_INSTRUCTION`. |
| aCommandToOneServerReturnsTheReplyTheErrorOrWhyThereWasNone | `command` returns OK replies for plain text (sent as echo) and JSON, an ERROR reply with `UNKNOWN_INSTRUCTION` or `INVALID_PARAMETERS` and no failure, the server's `INVALID_REQUEST` for malformed input sent as typed, FAILED with "Connection refused" for a stopped server, and rejects an unknown server, a blank, a two-line and an over-long command. |
| addingServersNumbersThemAfterTheHighestIdAndTheyAnswer | `addServers` adds servers numbered after the highest ID, all answer, and removing the last server frees its number. |
| removingAServerUnlistsItAndTheOthersKeepWorkingByTheirIds | A removed server is unlisted, its port is closed, it cannot be restarted or removed again; servers after the gap are still found by ID, new servers continue the numbering, and removing the last server drops the swarm. |
| addingServersRejectsUnknownSwarmsAndSizesPastTheLimit | Unknown swarm, a count below 1, and any size past 100 servers are rejected; exactly 100 is allowed. |
| callSendsOneCommandToOneServerAndShowsTheRawReplyOrTheFailure (console) | `call <id> <text or JSON>` prints the line sent, the raw reply and the status for OK, ERROR and FAILED outcomes, and reports an unknown server and missing arguments. |
| addAndRemoveGrowAndShrinkASwarmAndReportMisuse (console) | `add <name> [servers]` and `server remove <id>` change the swarm, and bad input prints a usage or error message. |
| serverCommandsStopAndRestartOneServerAndReportMisuse (console) | `server stop`, `server restart`, and error messages for unknown servers, unknown actions and missing arguments. |

### ConnectionPoolTest (9 methods)

| Test | What it verifies |
| --- | --- |
| successiveCommandsToOneServerShareAConnection | Ten commands to one server use one connection (the server logs one connection and ten requests). |
| anErrorReplyIsReturnedAsAValueAndTheConnectionStaysUsable | An error reply is returned, not thrown, and costs no connection. |
| aRestartedServerIsReachedAgainBecauseAStaleConnectionIsRetriedOnANewOne | After a server restart, the next command succeeds over a new connection. |
| aStoppedServerIsReportedAsAFailureNotRetriedForever | A command to a stopped server fails promptly. |
| concurrentCommandsEachGetTheirOwnConnectionAndTheirOwnReply | 20 callers sending 300 commands each get their own reply, with far fewer connections than requests. |
| theServerSnapshotOfAGossipFreeSwarmStillWorksOverThePool | The snapshot of a swarm without gossip works over pooled connections: polling three servers three times opens three connections, and the plugin check is asked once per server, not on every poll. |
| aLoadTestKeepsItsConnectionsOpenInsteadOfOpeningOnePerRequest | 600 load-test requests from 10 callers use at most 60 connections, and every request is counted by the servers. |
| aServerThatRejectsTheKeepAliveFieldIsServedOneRequestPerConnectionFromThenOn | A fake older server that rejects the keep-alive field: the first request is sent again without it and succeeds, later requests do not retry, and each uses its own connection. |
| closingThePoolResetsIdleConnectionsSoTheLoadTestLeavesNothingInTimeWait | Closing the pool drops its connections, a new pool opens fresh ones, and an invalid size is rejected. |

### SocketActivityTest (6 methods)

| Test | What it verifies |
| --- | --- |
| groupsOneConnectionsLinesIntoConnectRequestAndReply | A connect, an instruction and a reply line become three events with the right connection ID, client, message ID and status, and the counters. |
| countsErrorsAbortsAndInstructionsAndIgnoresOtherLines | Error replies, aborted requests (the stderr format) and per-instruction counts; admin lines, server-ready lines, blank and null input are ignored. |
| keepsServersApartAndOnlyTheNewestEventsPerServer | Servers are tracked separately, the buffer keeps the newest 300 events, and counters keep counting past it. |
| viewReturnsOnlyEventsAfterAGivenSequenceAndHonoursTheLimit | The `after` sequence and the limit. |
| installCopiesConsoleOutputToTheRecorderAndRestoresTheStreams | Console output still reaches the original stream, lines split across writes are joined, and closing restores System.out and System.err. |
| recordsRealServerTrafficFromTheirLogLines | Real servers' echo requests are recorded with counters, message IDs and client addresses. |

### UiServerTest (19 methods)

`fullMembersEndpointRejectsUnknownServersAndServersWithoutGossip` checks that the complete-member-list endpoint gives 404 for an unknown server and 502 for one without the plugin, and that a POST to it is refused. The two activity endpoint tests (`activityEndpointServesAServersSocketActivityAndOnlyNewEvents` and `activityEndpointSaysSoWhenNothingIsRecordedOrAServerHasBeenQuiet`) check counters, events, the `after` parameter, unknown servers (404), the "not recorded" answer, and that GET on an action path still gives 405. The five swarm-control tests are listed at the end of the table.

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
| thePageCanSendACommandToOneServerAndSeeTheReplyOrTheError | `POST /api/servers/<id>/command` returns the reply fields and raw reply, an error reply's code, and a 200 with `FAILED` for a stopped server; an unknown server gives 404, a missing or two-line command 400, GET 405 and a foreign `Origin` 403. |
| thePageCanAddServersToASwarmAndRemoveOne | `POST /api/swarms/<name>/servers` adds one or several servers (one with no body), `POST /api/servers/<id>/remove` removes one, bad counts and unknown swarms or servers give 400 or 404, a foreign `Origin` is refused, and refused requests change nothing. |
| thePageCanStartASwarmSendToItLoadTestItAndStopIt | `POST /api/swarms` starts a 3-server swarm that then shows in the snapshot; `/send` gets an OK reply from every server; `/test` verifies 60 requests with none failed; `/stop` removes the swarm. |
| stopAllClosesEverySwarm | `POST /api/stop-all` closes every swarm, and stopping when nothing runs is not an error. |
| swarmActionsRejectBadInputWithoutChangingAnything | Duplicate, malformed, too-small and too-large names or counts, a count sent as text, missing fields, invalid or oversized JSON bodies, and an unknown action all give 400 or 404 (and 405 for GET), and no swarm is added. |
| swarmActionsRefuseRequestsThatDoNotComeFromThePageThisServerServed | The swarm endpoints refuse (403) a foreign `Origin` or a missing `X-Swarm-UI` header, and start nothing. |
| theSnapshotKeepsAnsweringWhileALargeSwarmIsStillStarting | With a server start held open, `/api/snapshot` still answers 200 instead of waiting, then shows the swarm once it has started. |

### SwarmGossipTest (15 methods)

| Test | What it verifies |
| --- | --- |
| generatedConfigGivesEachServerItsSwarmAndSeedsPointAtTheFirstServer | Each server gets its swarm ID, servers 2 and up are seeded with the first server's real port, entries are removed on stop, and the file and property are removed on close. |
| failedStartupForgetsTheSwarmsConfigAndNoGossipMeansNoProperty | A failed start leaves no config behind; without gossip the property is never set. |
| settingsRejectHarnessOwnedAndBlankValues, parsesGossipOptions | `GossipSettings` and the `--gossip` and `--gossip-fast` options. |
| gossipOptionsAreMergedOverThePresetAndNeedGossip | `--gossip-option` values override the preset, and bad forms or reserved keys are rejected. |
| serversConvergeAndReportTheirViewsThroughTheSnapshot | Four servers each see all members ALIVE, none is isolated, and the snapshot carries the plugin's status. |
| swarmsStaySeparateAndUseTheirOwnSwarmId | Each swarm uses its own swarm ID and lists only its own members. |
| closedServerIsSuspectedByThePeersWhichStayHealthy | A closed server is rated SUSPECT or DEAD by its peers, which keep rating each other ALIVE. |
| leavingServerIsMarkedLeftByThePeersQuickly | After `leave`, the peers record LEFT and the departure completes. |
| aServerAddedToARunningSwarmJoinsItAndARemovedOneIsMarkedLeft | A server added to a running gossip swarm is seeded at the first server, every server comes to rate all four ALIVE, and after a server is removed its peers rate it LEFT or DEAD. |
| restartedServerGetsANewBootIdAndRejoinsTheSwarm | A restarted server has a new boot ID, rejoins, and its peers rate the new boot ID ALIVE. |
| echoTrafficStillWorksWhileTheServersGossip | 300 echo requests succeed while the servers converge. |
| consoleStartsAGossipSwarmAndSendsAGracefulLeave | A console session starts a gossip swarm, sends a graceful leave to one server, and cleans up its config. |
| aSwarmLargerThanOneMembersPageIsReadWholeByFollowingTheCursor | With 40 gossip servers, one members page of 32 is cut short, and `SwarmSnapshot.fullMembers` follows the cursor to return all 40 members exactly once, each ALIVE. |
| swarmSeededAtAnotherSwarmRejectsItAndKeepsItsOwnMembers | A server seeded at another swarm's server has its exchange turned away, and neither swarm lists the other's members. |

### SwarmKvTest (8 methods)

Key-value replication in the harness, without any plugin jar.

| Test | What it verifies |
| --- | --- |
| kvReplicationIsOffByDefault | `--kv-replicate` is not set unless given. |
| theKvReplicateOptionSwitchesItOnAlongsideGossip | The option is accepted in any position next to `--gossip` or `--gossip-fast`. |
| kvReplicationNeedsGossipBecauseItFindsItsPeersThere | Using it without gossip is refused with a message that names both options. |
| theGeneratedFileTurnsReplicationOnAndIsNamedByTheKvConfigProperty | `KvConfigFile` writes a file containing `replication=on` and sets the `kv.config` property to its path. |
| closingRemovesTheFileAndTheProperty | Closing deletes the file and clears the property, and closing twice is harmless. |
| closingLeavesAPropertyThatNowPointsAtAnotherFileAlone | A property that has since been pointed elsewhere is not cleared. |
| aManagerAskedForKvReplicationHasTheConfigFileWhileItRunsAndRemovesItOnClose | A manager built with the flag creates the file when the first swarm starts, and removes it and the property on close. |
| withoutKvReplicationTheManagerLeavesTheKvConfigPropertyAlone | A manager built without the flag, or with the older constructors, never sets the property. |

### SwarmKvReplicationTest (5 methods)

Real servers with the real gossip and key-value plugins. They are skipped when either plugin has not been built in the sibling
project. Each waits for gossip to rate every member alive (its `members` list has a row for the server itself) before writing.

| Test | What it verifies |
| --- | --- |
| aWriteOnEachOfFiveServersBecomesReadableOnAllFive | A `put` on each of five servers in turn is readable on all five, and the value is the one written. |
| aDeleteOnOneServerIsSeenOnAllFive | A `delete` on a different server removes the pair everywhere. |
| anObjectValueSurvivesTheJourneyBetweenRealServers | An object with a nested object and an array reads back identically on another server. |
| statusOnTheWritingServerShowsReplicationOnAndItsPushes | `kv status` reports replication on and a push count that reaches the number of peers. |
| withoutKvReplicationAWriteStaysOnTheServerThatTookIt | With the flag off, a write stays on the server that took it, and `status` reports replication off. |

## Running the tests

The gossip tests start dozens of real servers. Since connections are kept open between exchanges, the whole harness suite adds about 3,000 sockets in TIME_WAIT. On Windows, avoid running the suite while another large gossip swarm is running that opens a connection per exchange, because that can leave the machine short of dynamic ports; see the 100-server note in the README.

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
