# Swarm Test Suite Overview

## Overview

The suite has 17 test methods (26 cases with parameterized inputs) in two JUnit 5 classes under `dev.mars.swarm`. Both start real servers on local ports, so they are integration tests rather than mocks.

- **SwarmManagerTest** covers the `SwarmManager` API: start, stop, status, send, test, list, validation and failure handling.
- **SwarmConsoleTest** covers the `SwarmMain` console, plugin-folder argument parsing, and JVM shutdown-hook cleanup.
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
