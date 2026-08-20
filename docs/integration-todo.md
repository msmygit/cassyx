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

## Frontend licensing states — needs backend

Implemented per state derivation and UI in `frontend/src/license/` (plan §9.4/§9.5). One gap found
along the way that is a backend/contract concern, not something to patch around on the frontend:

- **`CheckoutSessionRequest.email` is a required field**, but the "Purchase a license" CTA is
  reachable from states where the frontend has no email on file at all — `ABSENT` (first run, no
  trial started), `MALFORMED`, `INVALID_SIGNATURE`, and the legacy/`UNKNOWN`-state fallback for an
  older backend. Only `EXPIRED` and `UPGRADE_REQUIRED` retain a `status.email` to pre-fill.
  `ActivationScreen` currently calls `createCheckoutSession(email)` with `email` possibly
  `undefined`, and `endpoints.createCheckoutSession` falls back to `''` so the request stays
  well-typed — but an empty string is not a valid email and Stripe/the backend will presumably
  reject it. Either (a) make `email` optional on `CheckoutSessionRequest` and let Stripe Checkout
  collect it (Checkout can collect email itself when omitted), or (b) confirm the frontend must
  collect an email before calling checkout in those states and I'll wire a prompt. Not fixed here —
  `openapi/**` is out of scope for this workstream.
## Frontend 402 handling - needs backend

The frontend now reacts to `LicenseGateFilter`'s 402 (plan §9.1): the API client turns it into a
typed error, publishes it on a process-wide signal, and `LicenseGate` re-reads `GET /api/license`
and renders the activation screen for whatever state the status endpoint reports. Nothing here is
blocked, but three things are worth the backend/contract workstream's attention:

1. **The 402 body type is HAND-WRITTEN** (`LicenseGateState` / `LicenseRequiredDetails` in
   `frontend/src/api/errors.ts`), because the contract still does not describe the response or its
   `state` / `invitesPurchase` / `unlockHint` extension members - see "Licence gate - needs
   contract" above, which this depends on and does not duplicate. When that lands, the hand-written
   enum should become an alias of the generated `LicenseState`, and the frontend parser can drop
   its "unknown state" degradation to a pure type narrowing. The parser tolerates a 402 with no
   body, a non-problem+json body and an unrecognised `state` today, so a proxy-generated 402 still
   reaches the activation screen.

2. **The frontend deliberately renders from `GET /api/license`, not from the 402's `state`.** It
   treats the 402 purely as a trigger to re-check, on the strength of the §9.1 guarantee that the
   filter and the status endpoint read one `LicenseGate` bean and cannot disagree. If that
   guarantee is ever relaxed, tell this workstream: the fallback would be to render the activation
   screen straight off the 402 body, which is strictly worse (the 402 carries no `name`/`email`, so
   an expired customer would lose the checkout pre-fill).

3. **SSE cannot see the 402.** `EventSource` exposes neither the status code nor the body of a
   failed response, so `GET /api/jobs/{id}/events` behind the gate looks exactly like a dropped
   connection. The client now closes the stream (rather than reconnecting against the wall forever)
   and issues ONE `fetch` probe of the same URL to find out whether it was a 402. A cheaper answer
   would be for the gate to be irrelevant here - i.e. for the stream to be opened only once the
   instance is licensed - but the probe is a request per dead stream and worth knowing about if
   anyone reads the access log and wonders. If a future auth scheme forces job streams onto
   `fetch` + `ReadableStream` (`readSseStream` already exists for that), the probe can be deleted:
   the status would then be readable directly.

## Site licence, needs frontend

Backend side is done (plan §9.2): `GET /api/license` can now return `edition: "site"`, and a
refused bypass reports `enforce: true` / `bypass: false` rather than claiming to be bypassed.
The UI still needs:

- **A `site` badge.** `edition: "site"` is a GRANTED licence, not a bypass: it must NOT render the
  yellow `unlicensed-bypass` warning banner. Suggested copy: "Site licence, unlimited seats".
  Everything else about the screen is the same as a paid `standard` licence.
- **Time-boxed site licences exist.** `expires` / `daysRemaining` can be non-null on a `site`
  licence (an evaluation site licence), so the countdown must not be gated on `trial === true`.
  When it lapses, `state` is `EXPIRED` with `edition: "site"` and `trial: false`, the copy should
  say the site licence expired, not that a trial did.
- **Do not infer "unlocked" from the `enforce` flag alone.** The API now reports the EFFECTIVE
  value: a release build that was given `CASSYX_LICENSE_ENFORCE=false` reports `enforce: true`,
  `bypass: false`. Render on `licensed` + `state`, and use `bypass` only to decide the bypass
  banner.
- **Locked-screen copy for release builds.** When `licensed: false`, the purchase screen should
  mention that a free site licence is available for CI, evaluation and enterprise use, since
  telling those users to flip an env var no longer helps them in a published image.

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

## Licence gate - needs contract

`LicenseGateFilter` (plan 9.1) now refuses every gated `/api/**` request with `402 Payment
Required` and an RFC 9457 `application/problem+json` body. `openapi/cassyx-api.yaml` is off-limits
to this workstream, so the contract does not yet describe any of it. Needed:

1. **A `402` response on every gated operation.** Every operation outside `/api/health`,
   `/api/license/**` and `/api/billing/**` can now return `402`, and none of them declare it. Plan
   2.3 forbids bare 4XX, so this is a per-operation `$ref` to the shared problem schema - most
   cheaply expressed as a `components/responses/LicenseRequired` reused everywhere.

2. **A problem variant carrying `state`.** The body extends the shared RFC 9457 shape with three
   extension members, and the generated client currently types none of them:

   | member | type | meaning |
   | --- | --- | --- |
   | `state` | enum: `VALID` `BYPASS` `EXPIRED` `ABSENT` `MALFORMED` `INVALID_SIGNATURE` `UPGRADE_REQUIRED` | which activation screen to render |
   | `invitesPurchase` | boolean | true for `EXPIRED`/`ABSENT`/`UPGRADE_REQUIRED` (plan 9.4) |
   | `unlockHint` | string | operator-facing remedy; differs between a dev and a release build |

   `state` is the same enum the `LicenseStatus` schema already uses for `GET /api/license`, so it
   should be lifted to a named `LicenseState` schema and referenced from both rather than
   duplicated - the filter and the status endpoint are required never to disagree, and two copies
   of the enum is how that starts.

3. **`type` registration.** The problem `type` is `https://cassyx.dev/problems/license-required`,
   alongside the existing `https://cassyx.dev/problems/*` URIs.

Until this lands, the frontend must treat a `402` from any `/api` call as "licence problem, read
`state`" without generated types for the body.
## Billing and licensing service - needs

From the workstream that built `StripePaymentProvider`, `/api/billing/**` and `licensing/`
(plan §9.3, §9.4). None of these are blockers; each is a change in a file this workstream does not
own.

1. **`cassyx.billing.*` is not passed to `StripePaymentProvider`.** The `PaymentProvider` bean in
   `CassyxCoreConfiguration` is ServiceLoader-selected by id, and cassyx-api may not import
   `io.cassyx.license.impl..` (ModularityArchitectureTest, plan §2.1). So the Stripe provider reads
   its credentials from the ENVIRONMENT (`STRIPE_SECRET_KEY`, `STRIPE_WEBHOOK_SECRET`,
   `CASSYX_BILLING_API_URL`, optional `CASSYX_BILLING_INTEGRATION_ID`) — the same variables
   `application.yml` binds `cassyx.billing.*` from, so a normal Docker deployment works. Setting the
   YAML properties WITHOUT the env vars would not. The clean fix is a factory in
   `io.cassyx.license.api` (owner: whoever owns `PaymentProvider.java`), e.g.
   `LicenseFactory.paymentProvider(String id, Map<String,String> config)`, after which
   `CassyxCoreConfiguration` can hand it `BillingProperties`.
2. **`PaymentProvider.CheckoutRequest` has no `quantity`.** The contract's
   `CheckoutSessionRequest.quantity` therefore travels in `metadata["quantity"]` and
   `StripePaymentProvider` reads it back for the line item. A `quantity` component on the record
   would remove the indirection.
3. **No Spring Security is on the cassyx-api classpath**, so "webhook is CSRF-exempt" (§9.3) is
   currently satisfied by there being no CSRF filter at all. Whoever adds the licence gate /
   Spring Security must explicitly exclude `POST /api/billing/webhook` from CSRF **and** from the
   licence gate, and must not install a filter that consumes the request body — the Stripe
   signature covers the RAW bytes and any re-read breaks verification.
4. **`frontend/src/api/schema.d.ts` is stale by one generation.** `make contract` regenerates it and
   the billing 500 response added to `/api/billing/webhook` shows up there; the regenerated file was
   reverted rather than committed, since `frontend/**` belongs to another workstream. Re-run
   `npm run gen:api`.
5. **`cassyx-license` now enforces its §11.1 coverage gate** (`cassyx.coverage.skip=false` in its
   own POM, line ≥ 0.90, measured 92.3%). Whoever adds the `site` edition to `License.java` should
   expect `mvn verify` on that module to require tests for it.
6. **`licensing/` is outside the default reactor**, behind `-Plicensing` in `backend/pom.xml`, so
   `backend/Dockerfile` (whose build context is `./backend`) is unaffected. If the licensing service
   should be part of `make up`, it needs its own compose service built from `licensing/Dockerfile`
   with the repository root as context — deliberately not added here, since `docker-compose.yml`
   and the root `.env.example` belong to other workstreams. Its env template is
   `licensing/.env.example`.
7. **Email is a stub.** `LicenseEmailSender` has one implementation (`log`) that writes the key to
   the service log. A real provider is a new implementation plus one line in
   `LicensingConfiguration`; an unrecognised `CASSYX_LICENSING_EMAIL_PROVIDER` fails startup rather
   than silently falling back, so nobody believes customers are being emailed when they are not.
