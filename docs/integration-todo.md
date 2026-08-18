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
1. **Cancellation crosses engines.** `JobService.requestCancel(jobId)` (workstream D) must delegate
   to `DsbulkJobService.cancel(jobId)` for rows with `engine='DSBULK'`. Without it,
   `POST /api/jobs/{id}/cancel` returns success but never kills the DSBulk child process — the
   worst kind of bug, because the UI says cancelled while the job keeps consuming the cluster.
   D and E already share `DsbulkJobEventStream` as the SSE bus and the `cassyx_job` table, so this
   is the only remaining seam.
2. **`SessionRegistry` has no `@Bean`.** E injects via `ObjectProvider` and degrades to
   `DsbulkProbe.UNKNOWN`, so derived defaults stay generic until workstream A registers it.
   Same degradation likely applies to D and F.
3. **Frontend routes/nav not wired** (routes are orchestrator-owned): `LoadJobForm`, the Statistics
   tab hosting `CountStatisticsView`, and a job-template picker. All exported from
   `src/bulk/dsbulk/index.ts`.
4. **`s3.*` is not a real DSBulk settings group in 1.11.** Only `s3.clientCacheSize` exists;
   region/profile/credentials are `s3://` URL **query parameters** (region mandatory), and
   `sessionToken`/`endpoint` do not exist at all. The contract models them as settings. E kept the
   fields and documented the truth in help text, but translating them into URL query params is
   still to do. **Contract fix needed post-Phase-1** — this one is normative, not cosmetic.

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
