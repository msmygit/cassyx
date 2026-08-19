# cassyx — Implementation Plan

> **Note:** In plan mode I can only write to this file. On approval, step 0 is to copy this
> to `/Users/mrkr/Documents/00_coderepos/cassyx/docs/plan.md` as the canonical in-repo spec.

---

## 1. Context

### The problem

There is no good open tool for working with data in Apache Cassandra / DSE / Astra DB / other
CQL-compatible stores. The landscape today:

| Tool | Gap |
| --- | --- |
| `cqlsh` | CLI only. No grid, no schema tree, no export beyond `COPY`. Strong at `TRACING`/consistency. |
| DataStax DevCenter | Abandoned. |
| DBeaver CE | Cassandra driver is a PRO/plugin feature, not in the free core. |
| Beekeeper Studio | Cassandra is paid-tier only. |
| NoSQL Manager for Cassandra | $119, **Windows-only in practice**, closed source. Richest feature set — our functional benchmark. |
| DBVisualizer / TablePlus | Cassandra support is shallow ("quick checks"). |
| DSBulk | Excellent at bulk load/unload; CLI-only, HOCON config, no UI, no schema browsing. |
| Astra Console / DataStax Studio | Locks you to DataStax-managed infra. |

The prior-art prototype at `/Users/mrkr/Downloads/cqlens` (FastAPI + React, Dockerized) proves
the shape of the UI but is architecturally capped:

- Exports serialize **client-side** from an already-fetched result set → bounded by `LIMIT 100`.
- Auto-generated query hardcodes `LIMIT 100`; **no paging state / cursor handling anywhere**.
- Read-only: no DDL, no row CRUD, no INSERT/UPDATE/DELETE generation.
- No query history, saved scripts, multi-tab, result search/sort, or schema search.
- Single global backend driver session — no multi-user isolation.
- **Known bug in the reference screenshots:** dropping `demo.users` produced
  `SELECT * FROM system_auth.users LIMIT 100` — the drop handler resolves the keyspace from the
  wrong tree node. Do not port this logic; see §7.3.
- `/api/table_stats` exists with no UI; INDEXES and COMMENT modal tabs are never populated.

### The outcome we want

`cassyx` — a self-hosted, Dockerized web application (React frontend, Java backend) that is
simultaneously:

1. A **first-class CQL IDE and data manager** — matching the NoSQL Manager for Cassandra feature
   matrix (full object management, editable grid, CQL dump, keyspace copy, cross-DB import).
2. A **blazing-fast bulk data mover** — DSBulk-class unload/load throughput, driven from a UI,
   with DSBulk's full settings surface exposed but sane auto-derived defaults.
3. **Vector-native** — SAI, `vector<float, N>` columns, and ANN queries are first-class citizens,
   not an afterthought.
4. **Commercially sellable**, one-time Stripe payment unlocking the whole product, with free
   signed **site licences** for CI, evaluation and enterprise self-hosting (§9.2).

### Decisions already made (locked)

- **Form factor:** self-hosted web app, Docker Compose. Not desktop, not Electron.
- **Stack:** React + TypeScript frontend, **Java** backend.
- **Driver:** `org.apache.cassandra:java-driver-core` (the ASF-owned 4.x line, ASF since 4.18).
  Chosen over `scylla-rust-driver` specifically because it natively supports Astra's
  **secure connect bundle** via `CqlSessionBuilder.withCloudSecureConnectBundle(...)`, which the
  Rust driver does not. It is also the driver DSBulk is built on, so one driver serves both paths.
- **Licensing:** everything is one paid tier (no free/pro split). One-time payment. A free signed
  `site` licence unlocks CI, evaluation and enterprise deployments; the `enforce=false` bypass flag
  survives only in development builds (§9.2).
- **Scope:** everything lands in v1, including vector/SAI/ANN.
- **Build method:** parallel subagent workstreams (§10).

---

## 2. Architecture

```
┌──────────────────────────────────────────────────────────────────────┐
│  Browser — React 19 + TypeScript + Vite                              │
│  MUI v6 · TanStack Query · TanStack Table (virtualized) · CodeMirror6│
└───────────────┬──────────────────────────────────────────────────────┘
                │ REST + SSE (job progress) + streaming downloads
┌───────────────▼──────────────────────────────────────────────────────┐
│  cassyx-api      Spring Boot 3.5 · Java 21 · virtual threads         │
│  ├─ cassyx-core     session registry, schema catalog, CQL exec, paging│
│  ├─ cassyx-bulk     token-range parallel unload + DSBulk embedding    │
│  ├─ cassyx-vector   SAI / vector / ANN services                       │
│  ├─ cassyx-migrate  CQL dump, keyspace copy, JDBC import              │
│  └─ cassyx-license  Ed25519 verification + Stripe Checkout/webhooks   │
│                                                                       │
│  H2 (file mode) — connections, saved scripts, history, jobs, license  │
└───────────────┬──────────────────────────────────────────────────────┘
                │ CQL binary protocol v4/v5 (token-aware, LZ4)
┌───────────────▼──────────────────────────────────────────────────────┐
│  Apache Cassandra 3.11/4.x/5.x · DSE 5–6.9 · Astra DB (SCB)          │
│  Amazon Keyspaces · ScyllaDB                                          │
└──────────────────────────────────────────────────────────────────────┘
```

### Why this beats the cqlens architecture

The single most important design rule: **bulk data must never round-trip through the browser.**

- cqlens: driver → Python → JSON → HTTP → browser → `Blob` download. Caps at the result set.
- cassyx: driver → Java → encoder → `StreamingResponseBody` (or file on a mounted volume),
  with **N token ranges pulled in parallel**. The browser only ever receives a progress stream
  and a download handle.

### Monorepo layout

```
cassyx/
├── docs/plan.md                    ← this document
├── docker-compose.yml              app + cassandra (profile: dev)
├── backend/
│   ├── pom.xml                     Maven multi-module, Java 21
│   ├── cassyx-core/
│   ├── cassyx-bulk/
│   ├── cassyx-vector/
│   ├── cassyx-migrate/
│   ├── cassyx-license/
│   └── cassyx-api/                 Spring Boot app; produces the fat jar
├── frontend/                       Vite + React + TS
├── openapi/cassyx-api.yaml         ← CONTRACT. Written first. See §10.
└── e2e/                            Playwright
```

### Version pins

| Component | Version | Note |
| --- | --- | --- |
| Java | 21 LTS | virtual threads for per-range scan tasks |
| Spring Boot | 3.5.16 | stays on the 3.5.x maintenance line; carries spring-framework 6.2.19. Tomcat is overridden to 10.1.57, above the 10.1.55 Boot manages |
| `java-driver-core` | 4.19.3 | ASF-owned; `CqlVector` since 4.16, vector in QueryBuilder via JAVA-3118 |
| DSBulk | 1.11.2 | `com.datastax.oss:dsbulk-runner` |
| React | 19 | |
| Vite | 6 | |
| Stripe Java SDK | 33.3.0 | API version `2026-07-29.dahlia` |

> **Security pin required:** `mvnrepository` flags **CVE-2026-24400** and **CVE-2023-6378** against
> DSBulk 1.x transitive dependencies. Add explicit `<dependencyManagement>` overrides and wire
> OWASP Dependency-Check into CI. Do not ship without this — it is a blocker, not a nice-to-have.

**CVE-driven pins** (`backend/pom.xml`, plus `parquet`/`hadoop` in `backend/cassyx-bulk/pom.xml`).
These come from the first complete `dependency-check-maven:aggregate` run over the reactor, which
scans with `failBuildOnCVSS=7`. Every version below was checked against OSV and reports no known
vulnerability; lowering any of them re-opens the listed findings.

| Pin | Version | Clears |
| --- | --- | --- |
| `spring-boot` | 3.5.16 | 7 findings on 3.5.4 (CVE-2026-40974 9.8 worst), 21 on spring-core 6.2.9, 24 of the 30 on tomcat-embed-core 10.1.43 |
| `tomcat` | 10.1.57 | the 6 tomcat findings Boot's own 10.1.55 does **not** clear: CVE-2026-55276 / 53434 / 59083 / 59084 (9.1 each), CVE-2026-53404 (7.3). Needs explicit `<dependencyManagement>` entries for `-core`, `-el` and `-websocket`: `<tomcat.version>` alone is ignored under an imported BOM. CVE-2026-66299 is fixed only in the unreleased 10.1.58 and is suppressed (it is in the examples webapp, which the embedded jar does not contain) |
| `jackson-bom` | 2.22.2 | CVE-2026-54512/54513 (8.1); Boot 3.5.16's own 2.21.4 is still affected by CVE-2026-54515/59889 |
| `netty-bom` | 4.1.137.Final | maintenance line, ahead of Boot's 4.1.135.Final |
| `logback` | 1.5.34 | CVE-2023-6378 floor; 1.5.18 held logback *below* Boot's own version |
| `log4j-bom` | 2.26.1 | CVE-2026-34477 / 34479 / 49844 on Boot's managed 2.24.3 (all under the gate) |
| `parquet` | 1.18.0 | CVE-2025-46762 (7.1) and the jackson-databind **shaded inside** `parquet-jackson` (2.18.1 up to 1.15.2, 2.19.2 at 1.16.0, 2.21.3 at 1.17.1, clean 2.22.1 only at 1.18.0) |
| `hadoop` | 3.4.2 | CVE-2025-27821 (7.3) against hadoop-auth |
| `guava` | 33.7.1-jre | CVE-2023-2976 (7.1), CVE-2020-8908 on the 27.0-jre Hadoop drags in |
| `commons-lang3` | 3.20.0 | CVE-2025-48924 |
| `commons-configuration2` | 2.15.1 | CVE-2026-45205 |
| `commons-beanutils` | 1.11.0 | CVE-2025-48734 (8.8); a floor only, nothing resolves it since configuration2 2.15.1 |
| `aircompressor` | 2.0.3 | CVE-2025-67721 (6.3, under the gate) |

The jackson, netty, log4j and java-driver BOMs MUST stay imported **before** `spring-boot-dependencies`:
for imported BOMs it is first-declaration-wins, and a pin declared after Boot's BOM is silently
ignored. Suppressions live in `backend/dependency-check-suppressions.xml`, wired into the plugin in
`backend/pom.xml`; each one must carry a written justification (currently one entry, CVE-2023-37475,
which is a Go-only Avro CVE mis-matched onto `parquet-avro`).

### 2.1 Modularity & reusability contract

**Non-negotiable rule: every module must be usable without Spring, without the web layer, and
without the UI.** A developer should be able to add `cassyx-bulk` to an unrelated Java project and
run a token-range parallel unload with nothing but a `CqlSession`.

This forces a clean design and it is what makes the parallel agent workstreams (§10) possible at
all — agents can only work independently if the seams are real.

| Module | Depends on | Public entry point | Spring? |
| --- | --- | --- | --- |
| `cassyx-core` | driver only | `SessionFactory`, `SchemaCatalog`, `QueryExecutor`, `CqlLexer` | **no** |
| `cassyx-bulk` | `cassyx-core` | `UnloadEngine`, `LoadEngine`, `CountEngine`, `Encoder` SPI | **no** |
| `cassyx-vector` | `cassyx-core` | `VectorService`, `AnnQueryBuilder`, `SaiIndexManager` | **no** |
| `cassyx-migrate` | `core`, `bulk` | `CqlDumper`, `KeyspaceCopier`, `JdbcImporter` | **no** |
| `cassyx-license` | none | `PaymentProvider` SPI, `LicenseVerifier` | **no** |
| `cassyx-api` | all of the above | Spring Boot app — REST/SSE adapters only | yes |

Rules that enforce this:

- **Spring annotations exist only in `cassyx-api`.** Lower modules are plain Java with constructor
  injection; `cassyx-api` supplies `@Bean` wiring. Enforced in CI by an ArchUnit rule that fails
  the build on any `org.springframework` import below `cassyx-api`.
- **No module depends on a sibling's implementation package** — only on its `…api` package.
  ArchUnit-enforced.
- **Extension points are SPIs, not `if/else` chains.** `Encoder` (CSV/JSON/Parquet/XML/Excel),
  `Sink` (HTTP/file/S3), `PaymentProvider` (Stripe/Noop), `ImportSource` (CSV/Excel/MySQL/SQL
  Server), and `CapabilityProbe` are all `ServiceLoader`-discoverable. Adding a Parquet writer or a
  second payment processor means adding one class, not editing five.
- Each module publishes its own jar and its own README with a runnable snippet.
- **No shared mutable state between modules.** All cross-module communication is via the module's
  interface or an immutable value object.

### 2.2 One-command developer experience

Everything — app, database, tests, E2E — behind single commands. A `Makefile` (and an equivalent
`./cassyx` script for Windows/WSL parity) is the only entry point anyone needs to learn:

```
make up            # ⇐ THE one command: builds and starts the full stack
                   #   (Cassandra 5.x seeded with demo data + backend + frontend),
                   #   waits for health, opens http://localhost:8080
make down          # stop and clean volumes
make dev           # hot-reload mode: Spring DevTools + Vite HMR against the same Cassandra
make test          # unit + integration (Testcontainers) + frontend, with coverage gates
make e2e           # Playwright against a freshly seeded stack, headless
make e2e-ui        # same, headed, for debugging
make bench         # the §11 performance benchmarks
make verify        # everything CI runs, locally — the pre-push gate
make seed          # reload demo data (incl. a vector table for ANN)
```

`make up` must work on a clean checkout with **only Docker and Make installed** — no local Java,
Node, or Maven. Multi-stage Dockerfiles build inside containers. Cassandra readiness is a real
health check (`cqlsh -e "describe keyspaces"`), not a `sleep`. Ports and credentials come from a
committed `.env.example` copied to `.env` automatically on first run.

The seed dataset deliberately exercises the hard paths: collections, UDTs, tuples, counters, blobs,
static columns, a `vector<float, 1536>` column with an SAI index, a deliberately skewed partition
(for the §5.2 work-stealing test), and a wide 1000-column table (for the grid benchmark).

### 2.3 API contract — normative rules

`openapi/cassyx-api.yaml` is **the** coordination artifact. Eight Phase 1 workstreams implement
against it in parallel and the frontend generates its typed client from it, so a broken spec breaks
every agent at once. These rules are binding on every agent.

**Version and validation**

- **OpenAPI `3.1.1`** — the current patch of the 3.1 line. **Do not use 3.2.0**: it exists, but
  tooling (openapi-typescript, codegen, Swagger UI) still lags, and this file is a *build
  dependency* for the frontend. Do not use 3.0.x either — we rely on 3.1's full JSON Schema
  alignment (`examples` arrays, `const`, proper `null` typing).
- **The spec must lint with zero errors AND zero warnings:**
  ```
  npx @redocly/cli lint openapi/cassyx-api.yaml
  ```
  This is a CI gate (§11.1, `contract` job) and part of `make verify`. A red spec fails the build.
- **Every `$ref` must resolve.** The most common failure mode observed so far is writing paths
  without defining their `components/schemas` — it lints red and silently breaks `gen:api`. Count
  check: referenced schema names must be a subset of defined ones.

**Structural rules**

- Every operation has a unique camelCase `operationId` (these become generated client method names)
  and exactly one workstream tag: `connections`, `schema`, `query`, `bulk`, `vector`, `data`,
  `migrate`, `license`, `billing`, `capabilities`.
- No inline anonymous objects for anything non-trivial — every DTO is a named, referenced schema.
- Every operation declares its error responses using the shared **RFC 9457 `application/problem+json`**
  schema. No bare 4XX/5XX, no ad-hoc error shapes.
- Realistic `example` values throughout; examples must validate against their own schema.
- `servers` is a relative `/` plus documented variables — never a hardcoded localhost or example.com.
- SSE endpoints declare `text/event-stream` and define their event payload schema well enough for
  the frontend to type the stream.

**Security rules encoded in the contract, not just the implementation**

- Secrets are **write-only**: passwords, Astra tokens, keystore contents, and Stripe keys appear in
  request schemas only. Response schemas expose boolean presence flags (`hasPassword`) and never the
  value. Error responses must not echo them.
- Credentials never appear in path or query parameters — request body or header only, so they stay
  out of access logs and browser history.
- Ungated paths are exactly `/api/health`, `/api/license/**`, `/api/billing/**` (§9.1). Everything
  else is license-gated; reflect that in the spec's security definitions.

**Change protocol during Phase 1**

- The spec is **append-only** while Phase 1 runs. Adding operations or schemas is fine; renaming or
  changing the shape of an existing one breaks other agents mid-flight.
- A breaking change requires an explicit note to the orchestrator, who re-broadcasts to affected
  workstreams. Do not silently edit a schema another workstream is coding against.
- Contract first, always: **change the spec, then implement.** Backend endpoints that drift from the
  spec are a defect even when they work — the `contract` CI job catches drift by validating live
  responses against the schema (§11.1).

---

## 3. Connection management

Backing table: `cassyx_connection`. Credentials encrypted at rest with **AES-256-GCM**, key from
`CASSYX_SECRET_KEY` (env). **Secrets are never returned to the client** — the API returns
`hasPassword: true`, never the value.

Three connection modes:

| Mode | Fields |
| --- | --- |
| **Cassandra / DSE** | contact points (host:port list), local datacenter, username, password, protocol version override |
| **Astra DB** | Astra token (`AstraCS:…`) + secure connect bundle, acquired by one of three modes (§3.1) |
| **Advanced** | raw `application.conf` (HOCON) passthrough for exotic setups |

**SCB acquisition modes** — the Astra form has a mode selector; all three are first-class:

| Mode | When to use | Notes |
| --- | --- | --- |
| `AUTO_DOWNLOAD` *(default)* | normal use — token only | fetch via DevOps API (§3.1); no UUID typing |
| `UPLOAD` | air-gapped, restricted egress, or a hand-issued bundle | multipart upload, stored encrypted |
| `PATH` | Docker/K8s deployments mounting the bundle as a volume or secret | server-side filesystem path |

`PATH` mode resolves **on the backend host**, so the file must be visible to the *server* container,
not the user's laptop. This is the mode cqlens implemented as its only option, which is why cqlens
could not be deployed anywhere but the user's own machine — here it is one deliberate choice among
three, and the UI says plainly that the path is server-side. Guard rails: resolve against a
configurable allow-list root (`CASSYX_SCB_PATH_ROOT`, default `/etc/cassyx/scb`) and reject path
traversal outside it — an unrestricted server-side path parameter is an arbitrary-file-read
primitive. Validate the file is a readable zip containing the expected bundle entries before
attempting connection, so a wrong path fails with a clear message rather than a TLS error.

#### 3.1 Astra SCB auto-download (DevOps API)

Typing a database UUID and hunting for a bundle in the Astra console is the worst part of
connecting to Astra. Given only the Astra token, we can enumerate databases and fetch the bundle
directly. Modeled on DataStax's own `AstraDevOpsClient` in
[cassandra-data-migrator](https://github.com/datastax/cassandra-data-migrator/blob/main/src/main/java/com/datastax/cdm/data/AstraDevOpsClient.java),
with the deviations noted below.

**API contract (verified against that source):**

```
POST https://api.astra.datastax.com/v2/databases/{databaseId}/secureBundleURL?all=true
  Authorization: Bearer <AstraCS:…>
  Content-Type: application/json
  (no request body)

200 → JSON ARRAY, one node per datacenter:
  [ { "region": "us-east1",
      "downloadURL": "https://…",                       ← the "default" bundle
      "customDomainBundles": [ { "domain": "…", "downloadURL": "https://…" } ] },
    … ]
```

Selection algorithm: match the array element whose `region` equals the requested region
(case-insensitive); if no region is requested, take the first element. Then pick `downloadURL` for
type `default`, or search `customDomainBundles[]` for a matching `domain` for type `custom`.
Download the zip over plain HTTPS GET (no auth header on that URL — it is pre-signed).

**Deviations from the reference implementation — deliberate, do not "fix" back:**

1. **The reference has a latent bug we must not copy.** Its javadoc documents three SCB types —
   `default`, `region`, `custom` — but the `switch` only implements `default` and `custom`. Passing
   `region` falls through to `default:` and logs *"Unknown SCB type"*. Regional selection in fact
   happens via the `region` field match, independently of `scbType`. **Our model therefore has two
   orthogonal inputs — `region` (optional) and `scbType` ∈ {`default`, `custom`} — not three
   types.** Validate and reject anything else with a clear message.
2. **NPE guard:** the reference calls `scbType.toLowerCase()` without a null check. Default to
   `default` when absent.
3. **UX we add on top:** `GET /v2/databases` to populate a **database picker** (name, id, status,
   regions) so the user never types a UUID, and a **region dropdown** populated from the
   `secureBundleURL` response rather than free text. Auto-download is the default path; manual
   upload remains for air-gapped or restricted-egress installs.
4. **Storage:** the reference writes to a temp file with `deleteOnExit`. We store the bundle
   encrypted in H2 alongside the connection (same AES-256-GCM as credentials) and materialize it to
   a session-scoped temp file, so a connection stays reusable across restarts without re-downloading.
5. **Caching + refresh:** cache the bundle against `(databaseId, region, scbType, domain)` with an
   explicit "re-download bundle" action — Astra rotates bundles, and a stale bundle produces a
   confusing TLS failure rather than an obvious error.
6. **Egress awareness:** if `api.astra.datastax.com` is unreachable, fail with an actionable message
   pointing at manual upload — do not retry silently. Per §9.1 many Cassandra installs have no egress.

**Security:** the Astra token is a full-privilege credential. It is write-only in the API, encrypted
at rest, masked in the UI, and **never logged** — including in DevOps API error paths, which is
exactly where tokens usually leak. Add a test asserting the token never appears in log output.

> **Fix vs. cqlens:** cqlens took the SCB as a *local filesystem path*, which forces backend and
> bundle onto the same host and makes the tool undeployable. cassyx uploads the bundle, stores it
> encrypted in H2, and materializes it to a temp file scoped to the session's lifetime.
> Also: mask the Astra token in the UI (cqlens showed it in plaintext).

Plus, from the NoSQL Manager matrix: **SSH tunnel** support (JSch/sshd — local port forward before
session build), **SSL/mTLS** (truststore/keystore upload), and **multiple simultaneous cluster
connections**.

Session lifecycle: a `SessionRegistry` keyed by `(userId, connectionId)` holds `CqlSession`
instances with an idle-eviction TTL (default 30 min). `CqlSession` is expensive and thread-safe —
one per connection, never per request. Health-check endpoint drives the UI's connected indicator.

**Compatibility targets:** Apache Cassandra 2.1→5.x, DSE 4.0→6.9, Astra DB, Amazon Keyspaces,
ScyllaDB + ScyllaDB Cloud. Capability detection at connect time (§7.1) gates features per target.

---

## 4. Schema & object management

### Catalog

Read from `session.getMetadata()` — the driver maintains a live, event-driven schema cache; do not
poll `system_schema`. Expose keyspaces → tables/views/UDTs/UDFs/aggregates → columns/indexes.

Frontend tree carries the **fully-qualified identity on every node** (`{keyspace, table}`) so drag,
selection, and context menus resolve from the node's own payload — this is the direct fix for the
cqlens keyspace-resolution bug. System keyspaces collapse under a toggleable "Show system" filter.
Add the schema search box cqlens lacks.

### Full DDL coverage (v1)

Every object type gets a visual editor **and** a "Preview CQL" pane — the generated statement is
always shown and always editable before execution. Never execute generated DDL silently.

- **Keyspaces** — create/alter/drop; `SimpleStrategy` vs `NetworkTopologyStrategy` with per-DC RF
  pickers; durable writes.
- **Tables** — create/alter/truncate/drop; partition & clustering key builder with ordering;
  static columns; full `WITH` options surface (compaction strategy + subproperties, compression,
  caching, `bloom_filter_fp_chance`, `gc_grace_seconds`, `default_time_to_live`, `read_repair`,
  `speculative_retry`, index intervals).
- **Columns** — add/drop/rename/alter, incl. collections (`list`/`set`/`map`), frozen types,
  tuples, counters, `vector<float, N>`.
- **Indexes** — **SAI** (primary path on C* 5.x / Astra), legacy 2i, and DSE Search. See §6.
- **Materialized views** — create/alter/drop with base-table awareness.
- **UDTs** — create/alter/drop, plus nested-type rendering in the grid.
- **UDFs & UDAs** — create/drop, language selection, `CALLED ON NULL INPUT` semantics.
- **Roles & permissions** — create/alter/drop role, `GRANT`/`REVOKE`, permission matrix view.

**Describe / DDL export:** use `TableMetadata#describe(true)`. Note **CASSJAVA-2** — older 4.x
patches emitted invalid CQL for vector-typed columns. 4.19.0 is required for correct vector DDL;
add a regression test asserting a `vector<float,1536>` column round-trips through `describe`.

### Table info panel

Reinstates what cqlens stubbed: **Fields** (name/type/kind — `partition_key`|`clustering`|
`regular`|`static` — with comments), **Indexes** (actually populated: name, target, kind, options),
**Comment** (editable), **Definition** (`describe`), and **Statistics** — the panel cqlens had an
API for but never built: per-node/per-token-range row estimates and top-N largest partitions,
sourced from the DSBulk `count` workflow (§5.4).

---

## 5. The fast data path

This is the differentiator. Three distinct engines, chosen per task.

### 5.1 Interactive query execution

For the CQL editor and grid — correctness and responsiveness, not raw throughput.

- **Server-side paging via driver `PagingState`.** Fetch size default 500. The paging state is an
  opaque token cached server-side against a result-set handle; the client requests
  `next`/`prev` pages. This directly fixes cqlens's `LIMIT 100` dead end.
- Statement-level `ConsistencyLevel`, `SERIAL` consistency, per-query timeout, `TRACING ON`
  equivalent (`setTracing(true)` → render the full `system_traces.events` timeline — one of the
  few places cqlsh currently beats every GUI).
- Multi-statement scripts: execute all / statement-under-cursor / selection — split by a real CQL
  lexer, not `split(";")` (string literals and UDF bodies contain semicolons).
- `BATCH` builder (logged/unlogged/counter) with a partition-key warning when a batch spans
  partitions.
- LWT (`IF NOT EXISTS` / `IF <cond>`) with `[applied]` surfaced distinctly in the grid.
- Async execution on virtual threads; every query cancellable from the UI.

### 5.2 Token-range parallel unload (native engine)

Our own implementation, used for interactive "export this table" and for streaming downloads.

```
1. tokenMap = session.getMetadata().getTokenMap()   // Optional<TokenMap>; must be enabled
2. ranges   = tokenMap.getTokenRanges()
3. splits   = ranges.flatMap(r -> r.splitEvenly(k)).flatMap(TokenRange::unwrap)
                                                    // unwrap is REQUIRED: CQL cannot express
                                                    // a wrapping range
4. per split: SELECT <cols> FROM ks.tbl
              WHERE token(pk) > ? AND token(pk) <= ?     // start-exclusive, end-inclusive
              with .setRoutingToken(split.getEnd())      // routes to the owning replica
5. work-stealing queue over an oversplit set; N virtual-thread consumers
6. ordered/unordered merge → encoder → sink
```

Two non-obvious correctness/performance points, both worth calling out to implementers:

- `splitEvenly(n)` splits by **token count, not data volume**. Under partition skew, equal-token
  ranges take wildly unequal time. **Oversplit (target ~10k splits) and use a work-stealing
  queue** rather than one-split-per-worker. This is the single biggest throughput lever.
- `unwrap()` before querying, always. The wrapping range around the ring minimum will silently
  return wrong results otherwise.

Encoders: **CSV, JSON / JSONL, Parquet, XML, Excel (.xlsx)**. Excel and XML come from the NoSQL
Manager parity list; Parquet is our addition and the right default for analytics handoff.
Sinks: HTTP streaming download, mounted volume path, or S3.

### 5.3 DSBulk-embedded engine (load, and very large unloads)

Dependency: `com.datastax.oss:dsbulk-runner:1.11.1`.

**Embedding contract.** DSBulk has no fluent programmatic API — the runner parses the same
`String[]` you'd pass on the CLI and dispatches to a workflow. Two gotchas that will bite:

1. **No `conf/` directory when embedded.** Either pass every setting as an argument or generate a
   HOCON file and point `-f` at it. We generate a per-job HOCON file into the job's temp dir —
   this also gives users a downloadable, reproducible artifact.
2. **HOCON collision.** DSBulk's `application.conf` collides with Spring/Typesafe Config on the
   same classpath. **Mitigation: run DSBulk in a separate JVM process** (`ProcessBuilder`) rather
   than in-process. This also gives free job isolation, cancellation (kill the process), memory
   capping, and immunity to DSBulk's `System.exit()` behavior. Parse the runner's exit status and
   tail its log directory for progress. Ship the DSBulk distribution (zip/tar.gz — **not** the
   executable jar, which upstream marks as evaluation-only) inside the Docker image.
3. Workflows are discovered via **ServiceLoader**; verify `dsbulk-workflow-unload`,
   `-load`, and `-count` are all present on the classpath in the shipped image.

**Settings surface — expose as much as possible, per the user's directive.** The UI presents every
DSBulk setting group, organized as progressive disclosure: a **Simple** tab (source/target, format,
mapping) and an **Advanced** accordion covering all of `connector.csv`, `connector.json`, `schema`,
`batch`, `codec`, `engine`, `executor`, `log`, `monitoring`, `driver`, `s3`, and `stats`. Every
field renders its upstream default as placeholder text and links to the DSBulk settings docs.
A **"View generated command"** pane shows the exact `dsbulk` invocation — copyable, so the UI
doubles as a DSBulk command builder even for users who'll run it elsewhere.

**Derived defaults (the important part).** Users should never *need* the advanced tab. At job
creation, probe the cluster and derive:

| Setting | Derivation |
| --- | --- |
| `executor.maxPerSecond` | unthrottled for self-managed; **respect server-side rate limiting on Astra** (DSBulk ≥1.9 detects and honors it — leave enabled) |
| `executor.maxInFlight` / `engine.maxConcurrentQueries` | f(node count × cores, capped by client cores); start `nodes × 32` for unload |
| `batch.mode` | `PARTITION_KEY` for load; `DISABLED` when the target has no clustering key |
| `batch.maxBatchStatements` | 32; drop to 1 for counter tables |
| `driver.basic.request.consistency` | `LOCAL_ONE` unload · `LOCAL_QUORUM` load (`LOCAL_ONE` for Keyspaces) |
| `driver.advanced.protocol.compression` | `lz4` |
| `connector.csv.maxConcurrentFiles` | = split count for unload |
| `schema.splits` | `nodes × cores × 8`, oversplit per §5.2 |
| `codec.*` | timestamp/date/time formats inferred from column types; `nullStrings` from a sniffed sample |

Show every derived value in the UI as an editable, clearly-marked "auto" chip so the user sees
*why* and can override. Persist overrides as reusable **job templates**.

### 5.4 Count / statistics

DSBulk `count` workflow: total rows, per-replica, per-token-range, and top-N largest partitions.
This powers the Statistics tab (§4) and pre-flight estimates for export jobs.

### 5.5 Job infrastructure

All long-running work (unload, load, count, dump, copy, import) is a **Job**: persisted row,
`QUEUED → RUNNING → SUCCEEDED|FAILED|CANCELLED`, progress via **SSE** (`/api/jobs/{id}/events`),
cancellable, with retained logs and a downloadable artifact. Bounded executor, configurable
concurrent-job cap. This is a shared substrate — build it before the engines that use it.

---

## 6. Vector, SAI & ANN (first-class, v1)

Requires `java-driver-core` 4.16+ for `CqlVector` (which implements `Iterable` plus `List`-like
methods) and 4.19.0 for correct vector handling in `describe`, Schema Builder, and QueryBuilder
(JAVA-3118). **Also verify against CASSANDRA-19333** — a data-corruption bug in `VectorCodec`;
add a round-trip fidelity test over a large float vector as a guard.

**Schema:**
- `vector<float, N>` as a first-class type in the column editor, with dimension input.
- SAI index creation on vector columns with `similarity_function` selection
  (`cosine` | `dot_product` | `euclidean`) and source-model options.
- Full SAI lifecycle on scalar columns too: create/alter/drop/check, with the options surface
  from the Astra SAI docs.

**Query:**
- **ANN query builder**: pick vector column → paste/upload a query vector or reference a row →
  choose `LIMIT` → generates `SELECT … ORDER BY <col> ANN OF [...] LIMIT k`.
- Hybrid queries: SAI predicates + ANN in one statement.
- `similarity_cosine` / `similarity_dot_product` / `similarity_euclidean` projections shown as a
  score column, sortable.

**Display:**
- Vectors render as a compact sparkline + dimension badge, not 1536 comma-separated floats.
- Expandable inspector: full values, magnitude, and a similarity-to-selected-row comparison.
- Export encodes vectors as JSON arrays (CSV/JSON) and as native list types (Parquet).

---

## 7. Data browsing & editing

- **Virtualized grid** (TanStack Table + virtual) — 500-row pages, smooth over wide tables.
- **Table view and Card view** (NoSQL Manager parity; card view is genuinely better for wide
  Cassandra rows).
- **Inline editing** → generates `UPDATE … WHERE <full primary key>`, previewed before execution.
  Refuse to edit any result set that doesn't project the complete primary key, and say why.
- **Row CRUD**: insert dialog (TTL + timestamp options), delete with preview, copy/paste rows.
- **Generate INSERT / UPDATE / DELETE for selected rows** → into the editor or clipboard.
- Type-aware renderers/editors: collections, UDTs, tuples, `blob` (hex/base64 toggle),
  `timeuuid` (with decoded timestamp), `duration`, `inet`, counters, vectors.
- Null vs. unset distinction made visible — this matters in Cassandra and every other GUI hides it.
- Client-side result filter/sort/search (the whole set of gaps cqlens left open).

### 7.1 Capability matrix

A `ClusterCapabilities` probe at connect time (version + `system.local`/`system_schema` sniffing)
drives feature gating, because the target list is genuinely heterogeneous:

| Capability | C\* 3.11 | C\* 4.x | C\* 5.x | DSE 6.x | Astra | Keyspaces | Scylla |
| --- | --- | --- | --- | --- | --- | --- | --- |
| SAI | ✗ | ✗ | ✓ | ✓ (6.8+) | ✓ | ✗ | ✗ |
| Vector / ANN | ✗ | ✗ | ✓ | ✗ | ✓ | ✗ | ✗ |
| Materialized views | ✓(exp) | ✓(exp) | ✓ | ✓ | ✗ | ✗ | ✓ |
| UDF / UDA | ✓ | ✓ | ✓ | ✓ | ✗ | ✗ | ✓ |
| `TRUNCATE` | ✓ | ✓ | ✓ | ✓ | ✓ | ✗ | ✓ |
| Token-range scan | ✓ | ✓ | ✓ | ✓ | ✓ | ✗ (no `token()` scan) | ✓ |
| DSE Search | ✗ | ✗ | ✗ | ✓ | ✗ | ✗ | ✗ |
| Roles/permissions | ✓ | ✓ | ✓ | ✓ | partial | IAM | ✓ |

Unsupported features are **hidden with an explanatory tooltip**, never shown broken. Amazon
Keyspaces in particular needs the bulk path to fall back from token-range scan to plain paging.

---

## 8. Advanced data operations (NoSQL Manager parity)

- **CQL Dump** — backup a keyspace: schema DDL + data, to a single file or a directory tree.
  Options: schema-only / data-only / both, per-table selection, compression, and a restore path
  that replays the dump with progress. Built on §5.2.
- **Copy keyspace between clusters** — streaming cluster→cluster with schema recreation, RF
  remapping (source DC names rarely match target), and table selection. Never buffers to disk.
- **Duplicate table** within or across keyspaces.
- **Import from file** — CSV, JSON, Excel; column-mapping UI with type inference and a dry-run
  preview of the first N rows before committing. Backed by DSBulk load.
- **Import from external RDBMS** — MySQL and SQL Server (NoSQL Manager parity), via JDBC →
  schema suggestion (proposing partition/clustering keys from the source PK) → DSBulk load.
- **Export results** — CSV, JSON, XML, Excel from any grid; and full-table export via §5.2.
- **Saved / favorite scripts**, persisted across sessions, with folders — plus full query history
  with timing, and multi-tab editing.

---

## 9. Monetization: one-time Stripe payment

Per the decision: **one paid tier, everything included, one-time payment, plus free signed site
licences and a development-only bypass flag.**

### 9.0 Distribution licence

Cassyx is distributed under the **Elastic License 2.0** (ELv2); see `LICENSE` at the repository
root. Source-available, not OSI-approved: anyone can read, self-host, and modify it, but ELv2's
Limitations clause specifically prohibits (a) offering it to third parties as a hosted or managed
service, (b) moving, changing, disabling, or circumventing the licence-key functionality (or
removing/obscuring any feature that functionality protects), and (c) altering or removing licensing
or copyright notices.

Clause (b) is why ELv2 replaced an earlier, mistaken MIT licensing of this repository: MIT grants
unrestricted rights to redistribute a modified fork with the §9.1 licence check deleted, which makes
the entire monetization model in this section unenforceable as a matter of law. ELv2 does not change
anything about how §9.1 (the Ed25519 key gate) or §9.2 (the sanctioned bypass) work technically. The
sanctioned bypass remains a legitimate, in-licence route for development, CI, evaluation and
enterprise site deployments. What ELv2 adds is that stripping or circumventing the gate outside of
those sanctioned uses is now also a licence violation, not just a code change someone could make.

Be plain about what this does and does not do: **a licence is a legal control, not a technical
one.** ELv2 does not make self-hosted software tamper-proof; nothing running on hardware the
licensee controls can be. It raises the cost and consequence of bypass (breach of contract, loss of
licence, potential liability) without pretending to prevent it outright. The Ed25519 signing scheme
in §9.1 remains the technical control; ELv2 is what makes defeating it something other than free.

### 9.1 Enforcement model

Offline **Ed25519-signed license key**. The app embeds only a *public* key, so a leaked build
reveals nothing that can mint licenses, and the product works fully air-gapped — which matters
because self-hosted Cassandra clusters are frequently in networks with no egress.

```
license payload (base64url, dot-separated from signature):
{ "lic":"CSX-XXXX-XXXX-XXXX", "email":"…", "name":"…", "issued":"2026-08-17",
  "edition":"standard", "seats":1, "ver":1 }
```

- `LicenseService.verify()` — signature check against `CASSYX_LICENSE_PUBLIC_KEY` (compile-time
  constant, overridable for dev). No network call on the hot path.
- **Server-side gate: `LicenseGateFilter`**, a plain `OncePerRequestFilter` registered as a
  `FilterRegistrationBean` on `/api/*`. It refuses every `/api/**` request except `/api/health`,
  `/api/license/**` and `/api/billing/**` with **`402 Payment Required`** and an RFC 9457
  `application/problem+json` body carrying the `state` (`ABSENT`, `EXPIRED`, `INVALID_SIGNATURE`,
  `UPGRADE_REQUIRED`, `MALFORMED`), `invitesPurchase` and a build-appropriate `unlockHint`, so the
  frontend routes to the correct screen instead of a generic error. Non-`/api` paths (the SPA and
  its assets) are untouched: gating them would take down the very activation screen the 402 points
  at. Prefix matching is exact-or-subtree, so `/api/licenseholders` stays gated.
- **Not Spring Security, deliberately.** Its value is authentication and authorisation machinery,
  and section 12 records that cassyx assumes a single-user self-hosted instance - there are no
  principals, roles or sessions. What it would add is a filter chain, a servlet-wide security
  config, and CSRF protection that the Stripe webhook under `/api/billing/**` would then need an
  explicit exemption from: a new way to break payments in exchange for nothing. If real user
  accounts ever arrive, Spring Security can replace the filter and reuse the same `LicenseGate`.
- **One decision, two consumers.** `LicenseGate` (a single bean) holds the verifier, the
  `BypassPolicy` and the running version; the filter and `GET /api/license` both read it, so they
  cannot disagree. A gate that says "locked" while the status endpoint says "unlocked" is a support
  ticket; the reverse is the product given away. `LicenseGateConsistencyTest` asserts the agreement
  across the whole (public key × key × enforce × bypass-allowed × version) matrix.
- **Fails closed.** Any exception from verification becomes an invalid verdict, never an allow. An
  unusable `cassyx.license.public-key` still leaves `/api/license` reachable reporting `MALFORMED`,
  because a configuration gap must be diagnosable rather than silent.
- **Hot path.** The verdict is cached for 30s and invalidated on activation - long enough to
  collapse a burst of API calls onto one Ed25519 verify, short enough that a lapsing trial does not
  survive until the next restart.
- **Version scoping is live.** The running major comes from `CassyxVersion`, sourced from
  spring-boot-maven-plugin's `build-info` goal and degrading to `1.0.0` (never `0.x`, whose
  `coversMajor()` is true for every scope and would make scoping fail silently open).
- Frontend: a `LicenseGate` provider; unlicensed state renders an activation/purchase screen.

### 9.2 Site licences, and the build-gated bypass flag

A documented `CASSYX_LICENSE_ENFORCE=false` unlocks the entire paid product with one env var, and
nothing in the code can tell the maintainer's CI from a customer who read the README. The flag's
users are legitimate (development, CI, evaluation, enterprise site deployments), so they get a
**signed credential** instead, and the free switch stops shipping in release builds.

**The `site` edition.** `edition: "site"` is an ordinary Ed25519-signed key verified by exactly the
same code path, there is no special case in the verifier, and forging one needs the private key.
Semantics: unlimited seats (`seats: 0`), normally perpetual and unscoped, but `expires` and `scope`
still apply when present, because a time-boxed evaluation site licence is a real thing we issue.
It is *granted*, so it is not the `unlicensed-bypass` sentinel: the UI names the edition rather
than warning about it. Site licences are issued free on request for CI, evaluation and enterprise
use.

```yaml
cassyx:
  license:
    enforce:        ${CASSYX_LICENSE_ENFORCE:true}   # false ⇒ fully unlocked, no checks
    bypass-allowed: @cassyx.license.bypass.allowed@  # BUILD-time gate, filtered by Maven
    key:            ${CASSYX_LICENSE_KEY:}
```

`bypass-allowed` is baked in at package time by a Maven profile in `backend/cassyx-api/pom.xml` -
`dev` (active by default) filters it to `true`, `release` to `false`, and `backend/Dockerfile`
builds `release` by default so every published image carries it. It is deliberately **not** an env
var: a runtime switch guarding a runtime switch guards nothing.

| `enforce` | `bypass-allowed` | Result |
| --- | --- | --- |
| `true` | either | Normal verification. A `site` key unlocks it, no flag involved. |
| `false` | `true` (dev build) | Bypass granted: synthetic licence, `edition: "unlicensed-bypass"`, `state: BYPASS`, `enforce: false`, banner visible. |
| `false` | `false` (release build) | Bypass **refused**: the flag is ignored, enforcement stays on, and a WARN naming `CASSYX_LICENSE_ENFORCE` is logged at startup so the operator is never left guessing. |

`GET /api/license` reports the **effective** state, never the raw flag: a refused bypass reports
`enforce: true`, `bypass: false` and the real `edition`/`state`, so `(enforce, bypass, edition,
state)` can never contradict itself. Claiming `enforce: false` while verifying licences would make
every client render an unlocked UI over a locked instance.

Honest limitation: this raises the cost of a casual bypass from "read the README" to "patch and
rebuild the jar". Self-hosted software the customer runs and can recompile is not tamper-proof and
this design does not pretend otherwise; it removes the *supported, documented* free unlock and
replaces it with a credential we can issue, scope and time-box.

### 9.3 Stripe integration (all placeholders, no live keys in repo)

Following current Stripe guidance:

- **Checkout Sessions API with `mode: "payment"`** — the recommended surface for one-time
  payments. Not PaymentIntents (that's for off-session), never the Charges API.
- **Instantiate `StripeClient`** and call methods on the instance. The global
  `Stripe.apiKey = …` pattern is deprecated across all current SDKs.
- **Never pass `payment_method_types`.** Omitting it enables dynamic payment methods, which is
  both the recommendation and better for conversion. To restrict methods later, use
  `payment_method_configurations` or `excluded_payment_method_types`.
- Pass `integration_identifier` (label + 8 random letters) for Dashboard attribution.
- Use a **restricted API key (`rk_`)** rather than a secret key.

**Fulfillment is webhook-driven, not success-page-driven** — this is mandatory, not optional. A
buyer can pay and lose connectivity before the return page loads; success-page fulfillment silently
drops those orders.

| Event | Action |
| --- | --- |
| `checkout.session.completed` | **only fulfill if `payment_status != "unpaid"`** |
| `checkout.session.async_payment_succeeded` | fulfill (delayed-notification methods) |
| `checkout.session.async_payment_failed` | mark failed, notify |

Fulfillment = mint an Ed25519-signed license → persist → email it. Always **verify the webhook
signature** (`Webhook.constructEvent`) before processing; make the handler idempotent on
`event.id`.

**Config — every value a placeholder:**

```yaml
cassyx:
  billing:
    enabled:          ${CASSYX_BILLING_ENABLED:false}
    provider:         stripe
    api-base-url:     ${CASSYX_BILLING_API_URL:https://api.stripe.com}
    publishable-key:  ${STRIPE_PUBLISHABLE_KEY:pk_test_PLACEHOLDER}
    secret-key:       ${STRIPE_SECRET_KEY:rk_test_PLACEHOLDER}
    webhook-secret:   ${STRIPE_WEBHOOK_SECRET:whsec_PLACEHOLDER}
    price-id:         ${STRIPE_PRICE_ID:price_PLACEHOLDER}
    success-url:      ${CASSYX_SUCCESS_URL:http://localhost:8080/activate?session_id={CHECKOUT_SESSION_ID}}
    cancel-url:       ${CASSYX_CANCEL_URL:http://localhost:8080/pricing}
```

**A `PaymentProvider` interface** abstracts the whole thing (`createCheckout`, `verifyWebhook`,
`parseFulfillment`) with `StripePaymentProvider` and `NoopPaymentProvider` implementations, so the
product can be resold through a different processor without touching license logic.

Endpoints: `POST /api/billing/checkout-session`, `POST /api/billing/webhook` (CSRF-exempt, raw
body preserved for signature verification), `POST /api/license/activate`, `GET /api/license`.

> **Note on the licensing service.** Minting licenses requires the *private* key, which must not
> ship in the self-hosted image. The webhook handler + key minter therefore run as a small
> separate deployment (`licensing/` — same repo, own Dockerfile, own Stripe secrets) that you
> operate. The distributed app only ever *verifies*. Sandbox setup for development:
> `npm i -g @stripe/cli && stripe sandbox create` produces working test keys with no registration.

**Delivery: the last step of fulfilment, and the one that decides whether a sale completed.** A
minted, persisted, unemailed key is a customer who paid and received nothing, so `licensing/`
selects a `LicenseEmailSender` on `CASSYX_LICENSING_EMAIL_PROVIDER`:

| Provider | Behaviour |
| --- | --- |
| `log` (default) | Writes the whole message, licence key included, to the service log. Development only, WARNs on every send. |
| `smtp` | Real delivery via `spring-boot-starter-mail`. multipart/alternative (plain text *and* HTML), body differing per `PURCHASE` / `TRIAL` / `RECOVERY`. |

An unrecognised value **fails startup**, never falling back: a silent fallback to `log` is
indistinguishable from a service that is emailing everybody correctly, which is the worst possible
failure mode for something on the payment path.

SMTP rather than a vendor API SDK, deliberately. Postmark, SES, Resend, Mailgun, Fastmail and Gmail
all speak SMTP, so one implementation covers whichever the operator picks and switching is an
env-var change rather than a code change; and it adds no vendor SDK to the one service holding the
Ed25519 private key. Three properties are load-bearing:

- **Timeouts on connect, read and write.** Jakarta Mail defaults all three to infinite, and this
  send runs inside the webhook handler - a hung SMTP socket would stall fulfilment for every buyer
  behind it and trigger Stripe redelivery on top. Failing is recoverable; hanging is not.
- **Bounded retries, transient only.** A permanent rejection (bad credentials, rejected recipient,
  any 5xx reply) is not retried at all. On giving up the sender throws, the licence is recorded
  undelivered, and `POST /licensing/recover` (which already exists) owns everything beyond that.
- **Never logs the key or the SMTP password.** The `log` provider prints keys because that is its
  entire purpose as a development tool; the SMTP path logs recipient, reason, licence code and the
  provider's own response, on the success and the exception path alike.

Deliverability is an operator prerequisite, not a nice-to-have: SPF, DKIM and DMARC on a sending
domain the operator controls, documented in `licensing/README.md`, because licence email filed as
spam is indistinguishable from licence email never sent.

### 9.4 Trial licenses

Nobody buys a database tool they have not pointed at their own cluster. Without a trial the funnel
is binary — locked or paid — and the bypass flag (§9.2) becomes the de-facto way everyone "buys".

Trials reuse the §9.1 mechanism entirely. The payload is versioned via `ver` precisely so it can be
extended without invalidating keys already in customers' hands, and **`expires` is that extension**:

```
{ "lic":"CSX-…", "email":"…", "name":"…", "issued":"2026-08-17",
  "edition":"trial", "seats":1, "ver":1, "expires":"2026-09-01" }
```

Rules, all implemented in `cassyx-license`:

- **Absent `expires` means perpetual.** Every key minted before trials existed keeps verifying
  unchanged. This is the property that makes the extension safe.
- **Expiry is inclusive.** `expires: 2026-09-01` is valid through the whole of 1 September. Buyers
  read "expires on the 1st" as "works on the 1st"; an off-by-one here reads as the product cheating
  them out of a day.
- **Signature is verified before expiry**, never the reverse — `expires` is only trustworthy once we
  know the payload was not edited. Checking expiry first would let anyone extend their own trial.
- **An unparseable `expires` fails closed** (`MALFORMED`), never falling back to perpetual. A
  malformed date silently upgrading a trial to a perpetual licence is the one direction this code
  must never get wrong.
- **UTC clock**, injected, so a licence does not lapse at a different instant depending on where the
  container runs — and so expiry is testable at all.
- Default trial length: **14 days** (`License.DEFAULT_TRIAL_DAYS`).

`LicenseState` distinguishes `VALID · BYPASS · EXPIRED · ABSENT · MALFORMED · INVALID_SIGNATURE ·
UPGRADE_REQUIRED`, because an expired trial deserves a purchase CTA while a bad signature deserves
an error — collapsing both into `valid=false` throws away the only conversion moment the product
gets. `LicenseStatus.invitesPurchase()` encodes which is which. An expired licence **retains** its
payload so checkout can be pre-filled with the evaluator's name and email.

`POST /api/license/trial` issues one. Minting needs the private key, so it proxies to the
operator-run `licensing/` service and activates the result locally; it returns `503` with a pointer
to `/api/license/activate` when there is no egress, and `409` rather than silently re-arming the
clock when an email has already had a trial.

### 9.5 Purchased version scope

One-time pricing means upgrade revenue is the only revenue from existing customers, so a key is
perpetual **for the major version it bought** and future majors are a paid upgrade. Encoded now,
because retrofitting version scope onto keys already in customers' hands means either honouring them
forever or breaking faith with your earliest buyers.

`"scope": 1` — the purchased major. Semantics:

- The licence covers that major **and every earlier one** (a v2 key runs v1 fine — scope is a
  ceiling, not an equality check).
- A newer major yields `UPGRADE_REQUIRED`, which invites purchase rather than erroring.
- **Absent `scope` means unrestricted**, so pre-scoping keys keep working everywhere.
- A stale key is a reason to withhold a **new release**, never to break a running install —
  upgrading is the customer's choice, and a licence must never stop working on the version it was
  sold for.

Scope checking is opt-in at construction (`Ed25519LicenseVerifier.UNSCOPED` disables it), so the
enforcement date is a deployment decision rather than a code change.

---

## 10. Build strategy — parallel subagent workstreams

Per the user's directive to use subagents and maximize parallelism.

### Phase 0 — sequential, blocking (one agent, must finish first)

Everything else depends on these contracts, so they are deliberately *not* parallelized:

1. `openapi/cassyx-api.yaml` — the complete API contract, per the normative rules in **§2.3**.
   **This is the coordination artifact.** Backend agents implement it; the frontend agent generates
   a typed client from it. Neither waits on the other.
   **Exit criterion — Phase 1 does not start until this passes:**
   `npx @redocly/cli lint openapi/cassyx-api.yaml` → **0 errors, 0 warnings**, every `$ref`
   resolving. Everything else in Phase 0 may proceed in parallel with it, but no Phase 1
   workstream begins against a red spec.
2. Maven multi-module skeleton + Spring Boot bootstrap + H2 schema (Flyway).
3. Vite/React skeleton + MUI theme + generated API client.
4. `docker-compose.yml` with a Cassandra 5.x service (vector/SAI need 5.x) for integration tests.
5. **Job substrate** (§5.5) — every long-running workstream builds on it.
6. **`Makefile` + Docker Compose one-command stack** (§2.2) and the CI pipeline (§11.1). Built in
   Phase 0 precisely so every parallel agent inherits `make test` and `make verify` from day one
   and no agent invents its own harness.

### Phase 1 — parallel workstreams (8 agents, independent)

| # | Workstream | Owns | Key risk |
| --- | --- | --- | --- |
| A | Connections & sessions | §3 — registry, SCB upload, SSH, SSL, crypto | credential handling |
| B | Schema catalog & DDL | §4 — metadata read, all object editors, describe | breadth; CASSJAVA-2 |
| C | Query engine | §5.1 — paging, tracing, CQL lexer, batch, LWT | lexer correctness |
| D | Native bulk engine | §5.2 — token-range scan, encoders, sinks | skew/work-stealing, `unwrap()` |
| E | DSBulk integration | §5.3/5.4 — process runner, HOCON gen, full settings UI, defaults | classpath/HOCON isolation |
| F | Vector / SAI / ANN | §6 — types, indexes, ANN builder, renderers | driver version pins |
| G | Data grid & CRUD | §7 — virtualized grid, editors, statement generation | PK-completeness rule |
| H | Licensing & Stripe | §9 — Ed25519, gate, provider abstraction, licensing svc | webhook idempotency |

> **Workstream H — already delivered in Phase 0, do not rebuild:** the Ed25519 verifier, trial
> expiry (§9.4) and purchased version scope (§9.5) are implemented in `cassyx-license` and covered
> by `TrialAndScopeTest` (12 tests), and `LicenseStatus` in the contract already exposes `state`
> (incl. `UPGRADE_REQUIRED`), `scope`, `trial`, `expires` and `daysRemaining`.
>
> **What is NOT done and is workstream H's job:** the frontend consumes only
> `{licensed, enforce, bypass, edition}` and **ignores `state` entirely**, so today an
> `UPGRADE_REQUIRED` or `EXPIRED` licence renders as a generic locked screen. Required:
> - `UPGRADE_REQUIRED` → an upgrade-purchase screen naming the purchased major vs. the running one.
>   §9.5 is explicit that this must *invite purchase*, not read as an error — it is a paying
>   customer, and the only thing standing between them and more revenue is the wording of this screen.
> - `EXPIRED` trial → a purchase screen showing the expiry date; a live `daysRemaining` countdown
>   while a trial is still valid.
> - `MALFORMED` / `INVALID_SIGNATURE` → distinct copy from `ABSENT`; "your key is corrupt" and
>   "you have no key" are different problems with different fixes.
>
> Also still open: minting (the private key lives only in the separate `licensing/` service, §9.3),
> Stripe Checkout + webhook fulfilment with `event.id` idempotency, and transactional email —
> which is on the fulfilment critical path, since if it fails a customer has paid and received nothing.

Then Phase 2 (integrating, 3 agents): migration tools (§8), capability matrix + compatibility
testing across the target matrix (§7.1), and E2E/perf benchmarking.

**Coordination rules for agents:**

1. **The OpenAPI contract governs.** Follow §2.3 without exception. Contract first — change the
   spec, then implement. The file is append-only during Phase 1; a breaking change requires an
   explicit note to the orchestrator, who re-broadcasts it.
2. **Run `npx @redocly/cli lint openapi/cassyx-api.yaml` before and after touching the spec.**
   If you find it red, stop and report — do not build on a broken contract, and do not "fix" another
   workstream's schemas without saying so.
3. Each workstream owns disjoint packages and disjoint spec tags. Do not edit another's.
4. Every agent ships integration tests against the shared Testcontainers Cassandra singleton.
5. No agent edits `pom.xml` parent dependency versions without flagging it.
6. Report honestly: paste real command output. A workstream reported green that is not green costs
   more than one reported blocked.

### Phase 3 - shipping

Building the product and *delivering* it are separate problems, and only the first was specified
above. `docker-compose.yml` builds both images from source, which is right for §2.2's clean-checkout
promise and wrong for everyone who bought the thing: a customer would need a source checkout and a
toolchain to run a binary they paid for.

Delivery is therefore two artefacts and one pipeline:

| Artefact | Purpose |
| --- | --- |
| `ghcr.io/<owner>/cassyx-backend`, `…/cassyx-frontend` | Published images, **linux/amd64 + linux/arm64**. Apple Silicon is too common in this audience for an amd64-only image; emulating a database tool's JVM is not a defensible default. |
| `docker-compose.release.yml` + `.env.release.example` | The pull-based stack. No `build:` stanzas, no bundled Cassandra (cassyx manages *your* clusters, §3). Two files, Docker, nothing else. |

`.github/workflows/release.yml` fires on a pushed `v*` tag: **guard → build → boot → smoke → push →
GitHub Release**. Three things about it are load-bearing:

1. **The tag must equal `<version>` in `backend/pom.xml`**, enforced by `scripts/release-version.sh`
   before anything is built. The reactor version is what `/api/health` reports and what licence
   scope is checked against (§9.5), so a mismatched tag ships an image that makes the wrong
   licensing decision for every customer. The version is parsed out of the pom (namespace-aware
   XPath on `/project/version`), not grepped (the file has ~40 other `<version>` elements).
2. **Verify before publishing, not after.** Tags are cut from `main`, which has already passed the
   §11.1 suite, so the release does not re-run tests. It runs the thing tests cannot: it builds the
   images, starts them via `docker-compose.release.yml` (the customer's file, so a mistake there
   fails the release rather than their evening) and runs `scripts/smoke.sh` against them, plus one
   release-only assertion that the built artefact reports the tagged version. A registry that never
   receives a broken tag beats a yank.
3. **Images are built with `--build-arg CASSYX_BYPASS_PROFILE=release`**, baking
   `cassyx.license.bypass-allowed=false` so a published image cannot be unlocked with
   `CASSYX_LICENSE_ENFORCE=false` (§9.2). That is the whole difference between selling the product
   and giving it away, so it is passed explicitly rather than inherited from a Dockerfile default.

Tag strategy from `v1.2.3`: `1.2.3`, `1.2`, `1`, `latest`. A prerelease (`v1.2.3-rc1`) publishes
only its exact version; `latest` must never silently move someone onto a release candidate.
`workflow_dispatch` runs the same pipeline as a dry run and pushes nothing; `make release-local`
does the same on a laptop.

---

## 11. Testing, CI & verification

### 11.1 Coverage strategy and CI

Efficient coverage means *targeted* coverage, not a uniform percentage. The gates differ by module
because the risk differs by module:

| Module | Line coverage gate | Rationale |
| --- | --- | --- |
| `cassyx-core` | 85% + mutation testing | correctness of paging/lexing is load-bearing |
| `cassyx-bulk` | 85% + **completeness property tests** | silent data loss is the worst failure mode |
| `cassyx-vector` | 80% | round-trip fidelity matters more than branch count |
| `cassyx-license` | 90% | a bypass bug is a revenue bug |
| `cassyx-migrate` | 75% | |
| `cassyx-api` | 60% | thin adapters; covered by integration + E2E instead |
| frontend | 70% statements | logic/hooks tested; presentational components via E2E |

Enforced by JaCoCo (`check` bound to `verify`, build fails under threshold) and vitest `--coverage`.
**PIT mutation testing** runs on `cassyx-core` and `cassyx-bulk` only — where line coverage most
easily lies about real assurance — with a 70% mutation-score gate.

Test pyramid, deliberately weighted: many fast unit tests (no containers, no network); one **shared
Testcontainers Cassandra 5.x singleton reused across the whole integration suite** (starting a
container per class is the usual reason Cassandra test suites become unusably slow); a thin E2E
layer covering only true user journeys.

CI (GitHub Actions), all jobs parallel where possible:

```
contract      → redocly lint (0 errors, 0 warnings) · all $refs resolve · gen:api produces
                clean TypeScript · live backend responses validated against the schema (drift check)
lint          → spotless/checkstyle · eslint · tsc --noEmit
arch          → ArchUnit: no Spring below cassyx-api, no cross-module impl imports (§2.1)
unit          → mvn test (matrix: Java 21) · vitest
integration   → mvn verify (Testcontainers Cassandra 5.x)
mutation      → PIT on core + bulk (nightly, not per-PR — too slow)
e2e           → make e2e (Playwright, trace + video retained on failure)
security      → OWASP Dependency-Check (CVE pins from §2) · gitleaks · npm audit
compat        → nightly matrix vs C* 3.11 / 4.1 / 5.0 / DSE 6.8 / Scylla (§11.3)
bench         → nightly, results committed to a trend file so regressions are visible
```

`make verify` runs the per-PR subset locally and is identical to what CI runs — no "works on my
machine" gap. Branch protection requires contract, lint, arch, unit, integration, e2e, and security.
The `contract` job runs **first and fast** — it is the cheapest signal that eight parallel
workstreams are still building against the same agreed API.

### 11.2 Test detail

**Unit / integration** — Testcontainers with Cassandra 5.x (vector + SAI available). Per
workstream: session lifecycle & SCB round-trip; DDL generate→execute→describe→compare for every
object type; paging across a 100k-row table with correct prev/next; token-range scan **completeness
assertion** (union of splits = full row count, no dupes, no gaps — the critical correctness test
for §5.2); vector round-trip fidelity at 1536 dims (CASSANDRA-19333 guard); license
sign/verify/tamper-detect; Stripe webhook signature verification with fixture payloads.

**End-to-end (Playwright)** — connect → browse schema → run query → page → edit a cell → export
CSV → run an unload job to completion → activate a license.

**Performance benchmarks (must be recorded, not assumed):**
- Unload 10M rows → CSV. Target: within 1.5× of native DSBulk CLI on the same hardware. Compare
  our native engine (§5.2) vs. embedded DSBulk (§5.3) and **document which wins at which scale** —
  this decides the default routing between the two engines.
- Grid first-paint on a 1000-column-wide table < 1s.
- Memory ceiling under a 50M-row unload — must stream, never buffer.

### 11.3 Compatibility

**Compatibility** — a smoke suite run against Cassandra 3.11, 4.1, 5.0, DSE 6.8, an Astra free-tier
DB, and ScyllaDB, asserting the §7.1 capability matrix gates correctly rather than erroring.

**Manual Astra check** — real SCB upload, connect, browse, ANN query on a vector table, unload.

---

## 12. Open items to confirm during build

1. **Auth model.** The plan assumes a single-user self-hosted instance. If multiple people will
   share one deployment, we need user accounts + per-user connection isolation — a meaningful
   addition. cqlens had a single global session; we should decide deliberately rather than inherit.
2. **DSBulk CVE pins** must be resolved before any public release (§2).
3. **Native vs. embedded engine routing** — the §11 benchmark decides this; until then, both ship.
4. Amazon Keyspaces lacks `token()` range scans — confirm the paging fallback is acceptable there.
