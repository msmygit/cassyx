# DSBulk `count` as a user-facing table feature — design analysis

Scope: plan §4 (Table info panel → Statistics), §5.2 (native token-range engine), §5.3 (DSBulk
out-of-process engine), §5.4 (count/statistics), §5.5 (job substrate), §7.1 (capability matrix).

Everything asserted about DSBulk below was read out of the **1.11.2 sources on Maven Central**
(`dsbulk-workflow-count`, `dsbulk-workflow-commons`) unless explicitly marked *unverified*. Where
something is unverified the doc says how to verify it rather than guessing.

---

## 1. Current state — what actually exists

Far more is built than the brief assumes. This is mostly a **wiring and correctness** job, not a
greenfield one.

### 1.1 `backend/cassyx-bulk` — essentially complete

| File | State |
| --- | --- |
| `api/dsbulk/DsbulkOperation.java` | `UNLOAD \| LOAD \| COUNT`; `command()` → CLI verb. Done. |
| `api/dsbulk/DsbulkJobSpec.java` | Carries `statsModes` + `topPartitions`; `DEFAULT_STATS_MODES = [global]`. Done. |
| `api/dsbulk/DsbulkCountReport.java` | `totalRows`, `perReplica`, `perTokenRange`, `largestPartitions`. Tokens are **strings** throughout (correct — Murmur3 spans full int64). Done. |
| `impl/dsbulk/DsbulkCountParser.java` | Parses the stdout report. Mostly right; see §4 for the residual gaps. |
| `impl/dsbulk/DsbulkDefaults.java` | `deriveStats()` emits `stats.modes` + `stats.numPartitions`; `normaliseStatsModes()` folds the bogus `biggest-partitions` into `partitions`; `SUPPORTED_STATS_MODES = {global, ranges, hosts, partitions}` — **confirmed exactly correct against 1.11.2** (`StatsSettings.StatisticsMode`). |
| `impl/dsbulk/DsbulkPlanner.java` | Emits a warning when a COUNT has no `partition*` mode. |
| `impl/dsbulk/ProcessDsbulkRunner.java` | Runs the child JVM, **stdout → file, stderr → pipe** (exactly right: `DefaultReadResultCounter.reportTotals()` writes to `System.out`, everything else is logback on stderr). Parses the count report from the stdout file on exit. `setsid` process-group cancellation. Done. |
| `impl/TokenRangeCountEngine.java` | Native `SELECT pk…, count(*) … WHERE token(pk) > ? AND token(pk) <= ? GROUP BY pk` engine, with a no-token-map fallback to a plain `SELECT count(*)`. **Exists but is wired to nothing** — no `CountEngine` bean, no controller path reaches it. |

`backend/Dockerfile` pulls `dsbulk-1.11.2.tar.gz` from GitHub releases and **fails the build** if
`dsbulk-workflow-{load,unload,count}-*.jar` is missing from `lib/`. The plan's ServiceLoader risk is
already closed at build time. `DsbulkDistribution.REQUIRED_WORKFLOWS` re-asserts it at runtime.

### 1.2 `backend/cassyx-api` — job path done, statistics path dead

- `CountJobController` → `POST /api/connections/{id}/jobs/count`, returns `202` + `Location`.
- `DsbulkJobService` runs it on the shared substrate: persisted row, `QUEUED→RUNNING→…`, named SSE
  events (`status`/`progress`/`log`/`completed`/`error`), cancellation via child-process kill.
- On success it builds a `DsbulkDtos.TableStatistics` and stores it **inside `settings_json`** on the
  job row.
- `TableController` serves `GET …/tables/{t}/statistics` from `io.cassyx.core.api.schema.TableStatisticsStore`.

**The break:** `TableStatisticsStore` is an `InMemoryTableStatisticsStore` bean created in
`SchemaConfiguration`, and *nothing ever calls `put()`*. The Javadoc even admits it: "Empty until
workstream E writes to it." So today:

- a count job runs, succeeds, and its result is buried in `settings_json`;
- `GET …/statistics` returns 404 forever;
- `TableInfo.statisticsAvailable` is always `false`.

**This single missing write is the highest-value fix in the whole feature.**

### 1.3 `openapi/cassyx-api.yaml` — already correct

`CountJobRequest`, `DsbulkStatsSettings`, `TableStatistics`, `ReplicaRowCount`,
`TokenRangeRowCount`, `PartitionSize`, `TableInfo.statisticsAvailable`,
`GET …/tables/{table}/statistics`, `POST …/jobs/count` all exist. Both stats enums read exactly
`[global, ranges, hosts, partitions]` — the `biggest-partitions` correction has landed.

### 1.4 Frontend — built, not stubbed

- `src/schema/TableInfoPanel.tsx` has a real `Statistics` tab (5th tab) rendering totals + largest
  partitions, with a "no statistics yet" `Alert` fallback.
- `src/routes/StatisticsPage.tsx` is a full page with a **Recalculate** button that posts a
  `CountJobRequest`.
- `src/bulk/dsbulk/CountStatisticsView.tsx` renders all four sections and — correctly — never passes
  tokens or int64 counts through `Number()`.
- `dsbulkSettingsCatalog.ts` exposes `stats.modes` / `stats.numPartitions` with the four real modes
  and `biggest-partitions` labelled as an alias.

The UI is therefore *permanently showing the empty state* because of §1.2. That is the user-visible
bug.

---

## 2. DSBulk `count` ground truth (verified against 1.11.2)

### 2.1 Modes

`StatsSettings.StatisticsMode` = `{ global, ranges, hosts, partitions }`. `dsbulk-reference.conf`:

```
stats {
  modes = [global]
  numPartitions = 10
}
```

`numPartitions` only applies when `modes` contains `partitions`. **Confirmed: there is no
`biggest-partitions`** — `partitions` *is* the N-biggest-partitions report. The contract correction
was right.

### 2.2 Exact stdout format — `DefaultReadResultCounter.reportTotals()`

```java
PrintStream out = System.out;
if (countGlobal)     { if (multiCount) out.println("Total rows:");            out.println(totalRows); }
if (countNodes)      { if (multiCount) out.println("Total rows per node:");
                       allAddresses.forEach(n -> out.printf("%s %d %.2f%n", node, total, pct)); }
if (countRanges)     { if (multiCount) out.println("Total rows per token range:");
                       allTokenRanges.forEach(r -> out.printf("%s %s %d %.2f%n", start, end, total, pct)); }
if (countPartitions) { if (multiCount) out.println("Total rows per partition:");
                       totalsByPartitionKey.forEach(c -> out.printf("%s %d %.2f%n", c.pk, c.count, pct)); }
```

with `multiCount = modes.size() > 1`. Facts that matter:

1. **Headers appear only when more than one mode is requested.** The repo's parser models this
   exactly (`headerOf` + `Section`). Correct.
2. **The last column on every multi-column line is a percentage, not a count.** Already handled.
3. **`hosts` and `partitions` lines have identical arity (3).** With a header they are
   distinguishable; with a single mode the parser falls back to an "is it an endpoint?" shape test.
   Correct in practice: `EndPoint.toString()` for a `DefaultEndPoint` is `/127.0.0.1:9042`.
4. **Order is fixed:** global, hosts, ranges, partitions.
5. **`hosts` and `ranges` emit a line for EVERY node / EVERY token range, including zero-count
   ones** (`allAddresses`, `allTokenRanges` are the full sets from cluster metadata). On a 12-node
   vnode cluster (256 tokens/node) `ranges` produces **~3 072 lines**. This is a payload-size
   problem, not a parsing problem — see §6.3.
6. **Partition-key rendering:** `PartitionKey.toString()` joins components with `|` and then does
   `.replace(' ', '_')` ("Remove spaces from output to preserve the number of columns"). So a
   composite key `('a b', 7)` renders as `'a_b'|7`. The parser treats it as one whitespace-free
   token — correct. But the UI should **not** present it as a literal key value; it is a rendering.
7. **Percentages use the default JVM locale** (`printf("%.2f")` without a `Locale`). In a
   comma-decimal locale the percentage column becomes `33,33`. The parser's `isLong()` strips
   commas, so `"33,33"` tests as a long — harmless for the shapes above, but it is a latent
   ambiguity. **Mitigation: pass `-Duser.language=en -Duser.country=US` to the child JVM.**
8. **`reportTotals()` runs in `close()`, "even if any failures"** — with an explicit
   `LOGGER.warn("the totals reported above are probably inaccurate…")` when the workflow did not
   succeed. So a partially-failed count still prints numbers. See §5.

### 2.3 Hard validation rules DSBulk enforces (all currently unhandled)

From `SchemaSettings` (1.11.2):

| Rule | Consequence today |
| --- | --- |
| `partitions` mode **requires a clustering column**: `"Cannot count partitions for table %s: it has no clustering column."` | `StatisticsPage.tsx` hardcodes `modes: ['global','ranges','partitions']`. On any table without a clustering key the count job **fails at init** with an opaque message. This is a live bug. |
| `schema.query` + any of `ranges`/`hosts`/`partitions` → `"only stats.modes = [global] is allowed"` | Not reachable today (the count controller never sets a query) but a `dsbulkSettings` override can set `schema.query`. |
| `schema.mapping` must not be set for count | Same: reachable via overrides. |
| `engine.dryRun` → `"Dry-run is not supported for count"` (thrown in `CountWorkflow.init`) | `CountJobController` passes `dryRun=false` and `DsbulkDefaults` only emits `engine.dryRun` for LOAD, so safe unless overridden. |

`inferCountQuery()` confirms the shapes: `global` selects the first partition-key column;
`ranges`/`hosts` select `token(pk…)`; `partitions` selects the whole partition key. All of them get
`appendTokenRangeRestriction()` — i.e. **every non-`global` mode, and in fact every mode, is a
token-range scan**.

---

## 3. Which engine — recommendation

**Route DSBulk for everything except the degenerate cases. Do not build a second user-facing count.**

Reasoning:

- **Out-of-process is non-negotiable and already paid for.** The HOCON collision (§5.3) is real, and
  `ProcessDsbulkRunner` already gives isolation, real cancellation, memory caps and `System.exit()`
  immunity. There is no upside to reopening that decision for count.
- **DSBulk's count is strictly more capable than `TokenRangeCountEngine`.** DSBulk gives per-replica
  attribution (which the native engine cannot produce — the driver does not tell you which replica
  served a row), checkpointing/resume, and a partition report bounded by a heap-safe top-N. The
  native engine accumulates a `List<PartitionStat>` per range and truncates *after* each range —
  a table with millions of partitions per range will still materialise them all first. That is an
  OOM waiting to happen and is a reason **not** to promote it.
- **Latency is not the differentiator here.** A count over a real table is seconds-to-hours either
  way; ~2 s of child-JVM startup is noise. The one place it isn't noise is a tiny table.

Concrete routing rule (implement in a `CountRouter` in `cassyx-api`):

| Condition | Engine | Why |
| --- | --- | --- |
| `TOKEN_RANGE_SCAN` unsupported (Amazon Keyspaces) **and** modes ⊆ `{global}` | **Native**, synchronous, via `TokenRangeCountEngine`'s no-token-map branch | DSBulk's inferred query always appends a token restriction; without `token()` support the workflow is not viable. A plain `SELECT count(*)` is the only honest answer, and it must be labelled "coordinator-side full scan". |
| `TOKEN_RANGE_SCAN` unsupported **and** modes include `ranges`/`hosts`/`partitions` | **Reject, 422** | Do not silently downgrade. The user asked for per-range data that cannot exist. |
| Table has no clustering column **and** modes include `partitions` | **Reject, 422** before submitting | Mirrors DSBulk's own validation, but at the API boundary with an actionable message, instead of an opaque job failure 30 s later. |
| Everything else | **DSBulk COUNT job** | |

`TokenRangeCountEngine` stays as an internal `CountEngine` used only for (a) the Keyspaces
`global` fallback and (b) unit-testable token-range completeness (§8). Its top-N partition path
should be deleted or bounded — it is not on any user-facing route and its unbounded accumulation is
a liability.

---

## 4. Output parsing — residual work

The parser is largely right. Remaining concrete items:

1. **`total` fallback ordering** — already correct (ranges before replicas; summing `hosts`
   multiplies by RF). Keep, and add a comment-level assertion test.
2. **Pin the child JVM locale** (§2.2 item 7): add `-Duser.language=en -Duser.country=US` to
   `DSBULK_JAVA_OPTS` in `ProcessDsbulkRunner`.
3. **`deriveStats()` reads the raw mode list, not the normalised one:**
   `spec.statsModes().contains("partitions")` misses `"Partitions"`, so `stats.numPartitions` is
   silently dropped and DSBulk's default of 10 applies. One-line fix: normalise first.
4. **Do not trust the report when the workflow did not succeed.** DSBulk prints totals anyway and
   warns they are inaccurate. `ProcessDsbulkRunner` currently parses unconditionally and lets
   `report.totalRows()` overwrite `rows`. Change to: parse always (the numbers are still useful in
   the job artifact) but mark the resulting `TableStatistics` as `partial: true` when
   `exitCode != 0`, and **never** write a partial snapshot into `TableStatisticsStore`.
5. **Bound the range list before it leaves the backend** (§6.3).
6. **Keep the raw stdout as a downloadable artifact** — already done (`dsbulk-stdout.log` is in
   `DsbulkResult.artifacts`). This is the escape hatch when parsing drifts.

*Unverified:* whether `bin/dsbulk` (the shell wrapper) ever writes anything of its own to stdout that
could precede the report. **Verify** by running the `DsbulkLoadCountIT` Testcontainers test with all
four modes and asserting the captured `dsbulk-stdout.log` contains only report lines.

---

## 5. Cost and safety

A count is a full cluster-wide scan of every row in the table. Make the consequence visible
*before* the job starts:

1. **Pre-flight estimate on the launch affordance.** Before `POST …/jobs/count`, call the existing
   `POST /bulk/command-preview` / defaults endpoint and show: derived `schema.splits`, derived
   `executor.maxInFlight`, the generated `dsbulk count …` command, and — when a prior snapshot
   exists — "last count: N rows in M s".
2. **An explicit consequence line, not a generic confirm.** e.g. *"This reads every row in
   `demo.users` from every replica. On the last run that was 4.2 B rows in 38 min. It will compete
   with live traffic."* Wire `executor.maxPerSecond` as a visible, editable throttle in that dialog —
   it already exists in the settings surface, it is just not surfaced at the point of decision.
3. **Reuse the snapshot.** `TableStatistics.computedAt` is already in the contract. The Statistics
   tab must render the cached snapshot with its age and require an explicit *Recalculate*, which
   `StatisticsPage.tsx` already does. Add the age chip.
4. **Feed the pre-flight estimate.** `DsbulkProbe.estimatedRows` already exists and is documented as
   "from a cached count job, or null when none has been run" — and is **never populated**.
   `DsbulkJobService.probe()` should read `TableStatisticsStore` and pass `totalRows` in. That closes
   the loop the plan describes: a count makes every subsequent export's ETA honest.
5. **Serialise counts per table.** Two concurrent counts of the same table are pure waste. Reject a
   second submission with `409` while one is `QUEUED|RUNNING` for the same `{connection, ks, table}`.

---

## 6. Capability gating (§7.1)

`Capability.TOKEN_RANGE_SCAN` already exists with the right Javadoc, is set per flavour in
`CapabilityMatrix`, and is exposed through `GET /api/connections/{id}/capabilities`.
`DsbulkProbe.supportsTokenRangeScan()` mirrors it.

Gating rules:

- **Backend:** the `CountRouter` table in §3 — 422 for `ranges`/`hosts`/`partitions` on Keyspaces,
  fallback to native `global` otherwise. Reuse `UnsupportedCapabilityException` so it renders as the
  standard `Problem`.
- **Frontend:** the mode checkboxes read `capabilities.tokenRangeScan`; when unsupported,
  `ranges`/`hosts`/`partitions` render disabled with the capability's `reason` string as the
  tooltip — the same pattern the rest of the app uses. `partitions` is additionally disabled when
  `TableInfo` shows no clustering columns, with reason *"`partitions` counts rows per partition and
  requires a clustering column."*
- `TableStatistics` for a Keyspaces `global` count carries empty `perReplica`/`perTokenRange`
  arrays; `CountStatisticsView` already tolerates that.

*Unverified:* whether Keyspaces rejects `token()` in the `WHERE` clause outright or merely does not
route it. Assume rejection. **Verify** only against a real Keyspaces endpoint — it cannot be
Testcontainer'd; until then the gate is defensive and costs nothing.

---

## 7. API shape

### 7.1 Job, not synchronous — and it already is

A count is unbounded in time (minutes to hours on a large table), must be cancellable, and must
stream progress. That is the definition of §5.5. `POST …/jobs/count` returning `202` + `Location:
/api/jobs/{id}` with SSE at `/api/jobs/{id}/events` is correct and already implemented.

The *result* read is separate and synchronous: `GET …/tables/{table}/statistics` returns the cached
snapshot. That split is also already in the contract and is right — the Statistics tab should not
have to replay a job's event stream to render a number.

The one exception is the Keyspaces `global` fallback, which is a single `SELECT count(*)`. Keep it
inside the same job envelope rather than adding a synchronous count endpoint: `SELECT count(*)` on a
large table through one coordinator can itself run for minutes and needs the same cancel/progress
affordances. Consistency beats saving one round trip.

### 7.2 OpenAPI diff

The contract is **already sufficient for the happy path**. Only three additions are needed, all
additive and backward-compatible. (A sibling agent owns this file; this is the diff to hand them.)

```yaml
# --- components.schemas.TableStatistics -----------------------------------
     TableStatistics:
       required: [identity, totalRows, computedAt, jobId]
       properties:
+        modes:
+          type: array
+          description: |
+            The `stats.modes` this snapshot was computed with. A snapshot from `[global]` has
+            empty `perReplica`/`perTokenRange`/`largestPartitions`, which is not the same thing
+            as "no skew" — the UI must distinguish "not measured" from "measured as zero".
+          items:
+            type: string
+            enum: [global, ranges, hosts, partitions]
+          example: [global, ranges, partitions]
+        partial:
+          type: boolean
+          default: false
+          description: |
+            The count job did not complete cleanly. DSBulk prints its totals even after a failure
+            and warns they are inaccurate, so the numbers are shown but flagged. A partial
+            snapshot is never cached as the table's statistics.
+          example: false
+        engine:
+          type: string
+          enum: [DSBULK, NATIVE]
+          description: |
+            `NATIVE` means a plain `SELECT count(*)` — the Amazon Keyspaces fallback, where
+            `token()` range scans are unavailable (§7.1). Only `totalRows` is populated.
+          example: DSBULK
+        perTokenRangeTruncated:
+          type: boolean
+          default: false
+          description: |
+            `ranges` mode emits one line per token range for the whole ring — thousands on a
+            vnode cluster. The array is capped; the full report is in the job's artifacts.
+          example: false

# --- paths ---------------------------------------------------------------
   /api/connections/{connectionId}/jobs/count:
     post:
       responses:
         "202": { ... }                     # unchanged
+        "409":
+          description: A count job for this table is already queued or running.
+          content:
+            application/problem+json:
+              schema: { $ref: "#/components/schemas/Problem" }
+        "422":
+          description: |
+            The requested modes are impossible for this target — `ranges`/`hosts`/`partitions`
+            against Amazon Keyspaces (no `token()` range scan), or `partitions` against a table
+            with no clustering column (DSBulk rejects it at workflow init).
+          content:
+            application/problem+json:
+              schema: { $ref: "#/components/schemas/Problem" }
```

Everything else — `CountJobRequest`, `DsbulkStatsSettings`, `ReplicaRowCount`,
`TokenRangeRowCount`, `PartitionSize`, `TableInfo.statisticsAvailable`, the `statistics` field on
`Job` — is already present and correct. **No breaking change is required.**

### 7.3 Payload bound

Cap `perTokenRange` at 512 entries server-side (largest-first), set `perTokenRangeTruncated`, and
leave the complete report in `dsbulk-stdout.log`, which is already an artifact. Without this a
`ranges` count on a 20-node vnode cluster ships ~5 000 objects into `settings_json` and into the
browser on every tab render.

---

## 8. Work breakdown

Ordered; each step is independently shippable.

**W1 — close the store gap (`cassyx-api`, `cassyx-core`).** *This is the whole user-visible feature.*
- Inject `TableStatisticsStore` into `DsbulkJobService`; in `finish()`, on a **successful** COUNT,
  map `DsbulkDtos.TableStatistics` → `io.cassyx.core.api.schema.TableStatistics` and `put()` it.
- The two `TableStatistics` records are duplicated (one per module, `computedAt` as `String` vs
  `Instant`). Collapse onto the `cassyx-core` one; `cassyx-api` already depends on core.
- Fix `partitionCount`: it is currently `largestPartitions().size()` — i.e. always 10. That is not
  the partition count, it is the top-N size. DSBulk does **not** report a total partition count.
  Set it to `null` and either drop it from the UI or, for a native count, populate it from
  `SELECT count(*)` over a `GROUP BY pk` — but do not fabricate it.
- Replace `InMemoryTableStatisticsStore` with a durable one (a row in the existing job schema, or a
  small `cassyx_table_statistics` table) so snapshots survive a restart. In-memory is acceptable for
  a first cut; note it in the UI as session-scoped if so.

**W2 — routing and validation (`cassyx-api`).**
- `CountRouter` implementing the §3 table; 422 with an actionable `Problem` for the two impossible
  combinations; 409 for a duplicate in-flight count.
- Add `modes`, `engine`, `partial`, `perTokenRangeTruncated` to the DTO.
- Cap `perTokenRange`.

**W3 — parser and runner hardening (`cassyx-bulk`).**
- Pin the child JVM locale.
- Normalise before the `numPartitions` check in `DsbulkDefaults.deriveStats`.
- Do not let a non-success `countReport` overwrite `rowsProcessed`; propagate a `partial` flag.
- Add the `partitions`-needs-clustering warning to `DsbulkPlanner.warnings()`.
- Bound or delete `TokenRangeCountEngine`'s partition accumulation.

**W4 — pre-flight reuse (`cassyx-api`).**
- `DsbulkJobService.probe()` reads the cached snapshot into `DsbulkProbe.estimatedRows`.
- Surface it as `totalRowsEstimate` / `percent` / `etaMillis` on the SSE `progress` payload for
  unload jobs (all three fields already exist in `JobProgress` and are never populated).

**W5 — frontend (owned by a sibling agent; hand over as a note).**
- Stop hardcoding `modes: ['global','ranges','partitions']` in `StatisticsPage.tsx`; derive from
  capabilities + clustering-column presence.
- Add the cost dialog (§5.2) and the snapshot-age chip.
- Disabled-with-reason mode checkboxes.
- Render `partial` and `perTokenRangeTruncated` banners.

---

## 9. Risks

| Risk | Severity | Mitigation |
| --- | --- | --- |
| **Stdout format drift on a DSBulk upgrade.** The report is `printf`, not a machine format. | High | Version-pinned distribution in the Dockerfile; a Testcontainers IT that asserts the parse for all four modes; raw stdout always retained as an artifact so a user can recover the numbers when parsing degrades; the parser already returns partial data rather than throwing. |
| **`partitions` on a table with no clustering column fails the job.** Live today. | High | W2 validation + W5 gating. |
| **`ranges` payload explosion on vnode clusters (thousands of lines).** | Medium | §7.3 cap + `perTokenRangeTruncated`. |
| **Cost surprise — a count is a full-table scan.** | Medium | §5: explicit consequence dialog, visible throttle, cached snapshot with age, no auto-run on tab open. |
| **`hosts` totals sum to `rows × RF`.** Users will read the per-node column as a partition of the total. | Medium | Never derive the total from `hosts` (parser already prefers `ranges`); label the per-replica table "rows served per replica (sums to rows × RF)". |
| **Locale-dependent percentage formatting.** | Low | Pin `-Duser.language=en`. |
| **Partition keys are a lossy rendering** (`\|`-joined, spaces → `_`). | Low | Label the column "partition key (rendered)"; do not offer click-through-to-rows from it. |
| **Amazon Keyspaces behaviour unverified.** | Low | Gate defensively; the fallback is a correct-if-slow `SELECT count(*)`. |

---

## 10. Test plan

### Unit — no cluster (`cassyx-bulk`)

1. **Parser golden files, one per mode and per multi-mode combination**, byte-generated from the
   1.11.2 `printf` formats in §2.2. `DsbulkOutputParsingTest` exists; extend it with:
   - single-mode `global` (bare number, no header);
   - single-mode `hosts` (3 columns, endpoint shape) vs single-mode `partitions` (3 columns,
     non-endpoint) — the arity-collision case;
   - single-mode `partitions` where the partition key is **numeric** (`5 5 33.33`) — must classify
     as a partition, not a host;
   - composite partition key (`'a_b'|7 12 4.00`);
   - full four-mode output with all headers, in DSBulk's fixed order;
   - a `ranges` line with a token at `Long.MIN_VALUE` — **assert the token survives as the exact
     string `-9223372036854775808`**, the int64 fidelity guard;
   - modes without `global`: total derived from ranges, not from hosts;
   - `hosts`/`ranges` lines with zero counts (DSBulk emits every node/range) — must be retained, not
     dropped, because "measured as zero" ≠ "not measured";
   - percentage rendered in a comma-decimal locale.
2. **`DsbulkDefaults.normaliseStatsModes`** — `biggest-partitions` → `partitions`; unknown modes
   dropped; empty → `[global]`; case-insensitivity; `numPartitions` emitted for every spelling that
   normalises to `partitions`.
3. **`DsbulkExitStatus`** ↔ upstream `ExitStatus` mapping (test already exists; extend to assert
   status 1 yields `partial = true` for a count).
4. **`ProcessDsbulkRunnerTest`** already fakes the child process — add a case whose fake stdout is a
   count report and whose exit code is 1, asserting `partial` and that no snapshot is cached.
5. **`CountRouter`** decision table as a pure unit test over `DsbulkProbe` + capability inputs: all
   four modes × {Cassandra, Astra, Keyspaces} × {clustering, no clustering}.

### Integration — Testcontainers Cassandra 5.x singleton

The count equivalent of §5.2's token-range completeness assertion is a **conservation law**:

> Insert a known, skewed dataset of exactly `N` rows across `P` partitions. Run a COUNT with all
> four modes. Assert:
> - `global == N`;
> - `sum(perTokenRange) == N` — **this is the completeness assertion**: no range double-counted, no
>   range missed, and it fails loudly if `unwrap()`/boundary semantics ever regress;
> - `sum(perReplica) == N × RF` (RF = 1 on a single-node container, so `== N`);
> - `largestPartitions` is sorted descending, has exactly `min(numPartitions, P)` entries, and its
>   head is the deliberately-skewed partition with the known row count;
> - `perTokenRange` entry count equals the number of ranges in `session.getMetadata().getTokenMap()`.

`DsbulkLoadCountIT` already exists as the harness. Additional ITs:

6. **`partitions` on a table with no clustering column** → job fails; assert the API rejected it at
   422 *before* submission rather than surfacing DSBulk's message.
7. **Statistics round trip:** run a count → `GET …/tables/{t}/statistics` returns 200 with matching
   totals → `GET …/tables/{t}` shows `statisticsAvailable: true`. This is the regression test for the
   §1.2 gap and must exist, because that gap survived a whole workstream by being invisible.
8. **Cancellation:** start a count over a table big enough to run for seconds, `POST
   /api/jobs/{id}/cancel`, assert terminal status `CANCELLED`, no orphaned JVM in the process group,
   and **no snapshot cached**.
9. **SSE:** subscribe to `/api/jobs/{id}/events`, assert `status(QUEUED→RUNNING)`, ≥1 `progress`,
   and a terminal `completed` — the count workflow's console reporter is the only progress signal, so
   this also guards `monitoring.console = true` / `log.ansiMode = disabled` never being turned off.
10. **Distribution:** assert `DsbulkDistribution.verify()` passes in the built image and that
    `dsbulk-workflow-count-*.jar` is present. The Dockerfile already fails the build on absence; add
    the runtime assertion to the smoke test so a mis-mounted `DSBULK_HOME` is caught at start-up.

### Not testable here

Amazon Keyspaces routing. Cover it with the `CountRouter` unit test over a synthetic
`ClusterFlavor.AMAZON_KEYSPACES` probe, and mark the live path as manually verified only.
