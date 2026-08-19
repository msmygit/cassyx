# Integration TODO — cross-workstream wiring

Phase 1 workstreams own disjoint files, so anything spanning a boundary lands here rather than
being edited by whoever noticed it. Orchestrator wires these once the relevant workstreams are
quiescent.

## Operational hazards (read before running builds)

- **Never run `mvn clean` over the whole reactor while agents are working.** It deletes other
  workstreams' `target/` output mid-run and cascades into `NoClassDefFoundError` across modules
  that were previously fine. Use `mvn -pl <module> verify` (no `clean`) while the tree is active;
  reserve `mvn -B clean verify` for a quiescent tree. Reported by workstream E after causing it.
- **Uncommitted work has been silently reverted at least once** (spec + two scripts, restored to a
  prior state while agents ran). Commit verified fixes promptly rather than letting them sit.

## Pending wiring

### From workstream E (DSBulk)
1. ~~**Cancellation crosses engines.**~~ **DONE.** `JobService.requestCancel(jobId)` now routes on
   the `cassyx_job.engine` column and delegates to `DsbulkJobService.cancel(jobId)` for
   `engine='DSBULK'`. `JobController` no longer routes as well — one place decides which engine owns
   a job. Two follow-on fixes were needed for the cancel to be truthful end to end:
   - `DsbulkJobService` tracks cancelled ids, because the worker thread wakes up moments later
     holding exit 143 (128 + SIGTERM) and used to overwrite `CANCELLED` with `FAILED`.
   - A row left non-terminal with no engine running it (a job whose worker died with a restart) is
     now recorded `CANCELLED` outright rather than answered 202 and left `RUNNING` forever.
   Covered by `JobEndpointsTest.cancelReachesTheDsbulkEngine`, which drives a REAL child process and
   asserts the process is gone as well as the row being `CANCELLED`.
2. ~~**`SessionRegistry` has no `@Bean`.**~~ **DONE.** `ConnectionsConfiguration` publishes exactly
   one, as `ManagedSessionRegistry`. The `ObjectProvider` fallbacks in `DsbulkJobService` and
   `SchemaSessions`, and the `@ConditionalOnMissingBean` no-op registry in
   `QueryModuleConfiguration`, are all removed — features get real capability data instead of
   `DsbulkProbe.UNKNOWN` / `409 NotConnected`. `ApplicationContextSmokeTest`
   `.exactlyOneSessionRegistryBeanIsPublished` guards both failure modes (two beans → ambiguous
   injection; one no-op bean → silent degradation). **Do not add a second bean.**
3. **Frontend routes/nav not wired** (routes are orchestrator-owned): `LoadJobForm`, the Statistics
   tab hosting `CountStatisticsView`, and a job-template picker. All exported from
   `src/bulk/dsbulk/index.ts`.
4. **`s3.*` is not a real DSBulk settings group in 1.11.** Only `s3.clientCacheSize` exists;
   region/profile/credentials are `s3://` URL **query parameters** (region mandatory), and
   `sessionToken`/`endpoint` do not exist at all. **Backend translation is now implemented**
   (`DsbulkS3Url`): the fields stay in the settings list so the UI can render them, and are folded
   into the connector URL when the HOCON and argv are rendered. `sessionToken`/`endpoint` are
   dropped with an explicit warning rather than written into a file Typesafe Config accepts and
   ignores. A URL carrying credentials is deliberately kept OFF the command line (`ps` exposes it,
   and DSBulk resolves `-url` above `-f`).

   **Contract change still needed — normative, not cosmetic.** `openapi/cassyx-api.yaml` must stop
   modelling these as settings:
   - Remove `s3.sessionToken` and `s3.endpoint` entirely. They do not exist in DSBulk 1.11 in any
     form, so any client generated from the current spec offers users fields that cannot work.
   - Re-document `s3.region`, `s3.profile`, `s3.accessKeyId`, `s3.secretAccessKey` as **`s3://` URL
     query parameters**, not settings, and mark `region` **required** whenever the sink URL is
     `s3://`. Today the spec implies a `dsbulk.s3.region` config key that DSBulk silently ignores.
   - Keep `s3.clientCacheSize` as the only member of the `s3` settings group.

## DSBulk count - needs contract

`openapi/cassyx-api.yaml` is owned by another workstream, so the statistics work landed against the
CURRENT contract and these additions are owed. Everything below is **additive** - no published
field changed meaning, and no shipped response violates the spec as written (neither
`TableStatistics` nor the count endpoint declares `additionalProperties: false`).

1. **Four optional `TableStatistics` fields, already emitted by the server.** The `perTokenRange`
   and `perReplica` sections are capped server-side at 500 rows
   (`DsbulkDtos.TableStatistics.MAX_DETAIL_ROWS`), ranked by row count before the cut. A 12-node
   cluster with 256 vnodes reports roughly 3000 token ranges, most of them empty; returning all of
   them is a large response and a table nobody can read. The cap has to be visible or a shortened
   list is indistinguishable from a small cluster:
   - `perTokenRangeTruncated: boolean`, `perTokenRangeReported: integer`
   - `perReplicaTruncated: boolean`, `perReplicaReported: integer`

   The frontend declares them locally in `CountStatisticsView.tsx` until the schema catches up.

2. **`partitionCount` is now always `null` for a DSBulk-sourced snapshot.** The field stays (it is
   already typed `[integer, "null"]`); only its description needs correcting. DSBulk's `count`
   workflow reports the top-N largest partitions and no total partition count. The old value was
   `largestPartitions().size()`, i.e. the top-N cap - every table in the world had exactly 10
   partitions. The example value `250000` should be dropped or marked as native-engine-only.

3. **`422` on `POST /api/connections/{id}/jobs/count`.** Two request/cluster combinations are
   refused up front rather than failing inside the child process minutes after the 202:
   - `partitions` on a table with no clustering column (DSBulk throws at workflow init);
   - `ranges` / `hosts` / `partitions` on a target with no `token()` range scan, i.e. Amazon
     Keyspaces (plan §7.1). `global` is NOT refused there - it falls back to the native paging
     engine.

   The problem body is `type: https://cassyx.dev/problems/count-mode-unsupported` with an extension
   member `modes: [string]` naming exactly what was refused, so the client can retry with the rest.

4. **Worth knowing, not a contract change:** a Keyspaces `global` count runs on the native engine
   but its `cassyx_job` row still records `engine='DSBULK'`, because that is what routes
   cancellation to the service owning the future. The settings document records
   `"engine": "NATIVE"` and the SSE `status` message says so. If the `engine` column ever gains a
   `NATIVE` value for these rows, cancellation routing in `JobService.requestCancel` must move
   with it.

### Known-broken at time of writing (each owned by an in-flight workstream)
- cassyx-core: 2 checkstyle violations (workstream C) block `mvn verify` for everyone.
- cassyx-api Spring context: workstream A's `ConnectionSessionService` has no default constructor,
  which fails `HealthEndpointTest` and `ApplicationContextSmokeTest` too.
- cassyx-bulk: does not compile mid-edit (workstream D) — `S3Sink`, `XlsxEncoder`,
  `ParquetEncoder`, `TokenRangeUnloadEngine` against a changed `UnloadEngine` signature.
- frontend: `src/ddl/**` and `src/schema/schemaApi.ts` red (workstream B).

These are expected mid-flight. They must all be green before Phase 1 is called done.

## Contract fixes already applied (commit 5be99d1)
Verified against upstream DSBulk `settings.md`, not assumed:
- `stats.modes` enum contained `biggest-partitions`, which does not exist upstream — `partitions`
  IS the largest-partitions report. This was normative (generated into client types), not an example.
- `fileNameFormat` example `%0,6d` → `%06d`; `allowExtraFields` example `false` → `true`;
  `retryPolicyClass` example named a class absent from the shipped distribution.

## Still outstanding from earlier phases
- **Licensing UI states** (workstream H, not yet launched): the frontend ignores `state` entirely,
  so `UPGRADE_REQUIRED` and `EXPIRED` render as a generic locked screen. §9.5 requires
  `UPGRADE_REQUIRED` to *invite purchase* — it is a paying customer and the wording of that screen
  is the entire upgrade-revenue path. Also `MALFORMED` vs `ABSENT` need distinct copy.
- **Stripe**: checkout, webhook fulfilment with `event.id` idempotency, the separate `licensing/`
  minting service (private key must never ship in the self-hosted image), transactional email.
- **Migration tools** (§8), compat matrix (§7.1), and the perf benchmark that decides
  native-vs-DSBulk routing (§11.2).

## Process-tree cancellation — CLOSED

**Fixed via process groups.** `ProcessDsbulkRunner` starts the child under `setsid`, so it leads its
own session and process group, and `cancel()` signals the whole **group** (SIGTERM, then SIGKILL
after the grace period) instead of the direct child.

The concern was real, and was reproduced before being fixed. In `maven:3.9-eclipse-temurin-21`, with
a child shaped like `bin/dsbulk` (a `/bin/sh` — dash — script that forks a JVM rather than `exec`ing
it):

```
before (process.destroy() only)        after (setsid + kill -TERM -<pgid>)
  PID PPID PGID COMMAND                  PID PPID PGID COMMAND
    1    0    1 java  (the API)            1    0    1 java  (the API)
   39    1    1 sh    <- killed           39    1   39 sh    <- killed
   40   39    1 sleep <- SURVIVED         40   39   39 sleep <- killed
  EOF+waitFor within 15s: false          EOF+waitFor within 15s: true
```

The survivor kept the inherited stderr pipe open, so `run()` never saw EOF and never returned —
which is also the deadlock the earlier `descendants()` attempt hit. That attempt failed because it
is a **TOCTOU race**, not because of anything subtle about the pipe drain: the snapshot is taken
before the child has necessarily forked, so the JVM it spawns afterwards is never signalled and
survives holding the pipe. A process group id is fixed by `setsid(2)` at exec time and inherited by
every later `fork()`, so it has no such window.

Two properties were verified rather than assumed:
- `setsid` **execs in place** for a child of the JVM (a JVM child is never already a process-group
  leader), so `Process.pid()`, `waitFor()` and `exitValue()` still refer to DSBulk itself.
- the group id is read back from `ps` and **refused if it equals the server's own group**, so a
  platform where `setsid` is missing or behaves differently degrades to killing the direct child
  rather than signalling the API process into oblivion. macOS has no `setsid`; that is the path it
  takes.

Guarded by `ProcessDsbulkRunnerTest.cancelKillsEveryDescendant`, which asserts no descendant
survives — not merely that the parent died. Negative control: with the group signal stubbed out the
test fails at 20.5s with `run() returned, so nothing is still holding the stderr pipe`.
