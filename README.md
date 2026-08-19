# cassyx

A self-hosted, Dockerized CQL IDE, data manager and bulk data mover for Apache Cassandra, DSE,
Astra DB, Amazon Keyspaces and ScyllaDB — vector/SAI/ANN native.

The authoritative specification is [`docs/plan.md`](docs/plan.md). This README covers the
developer experience (§2.2) and CI (§11.1).

---

## Quick start

**Requirements: Docker and Make. That is the entire list.** No local Java, Node or Maven —
every build, test and benchmark runs inside a container.

```bash
git clone <repo> && cd cassyx
make up
```

`make up` copies `.env.example` → `.env` on first run, starts Cassandra 5.x, waits for a **real**
health check (`cqlsh -e "describe keyspaces"` — never a `sleep`), seeds the demo dataset, builds
and starts the backend and frontend, waits for both to report healthy, and opens
<http://localhost:8080>.

On Windows / WSL / Git-Bash without GNU Make, use the parity wrapper:

```bash
./cassyx up
```

`./cassyx` delegates to `make` when it is available, and falls back to an equivalent built-in
dispatcher when it is not, so there is only ever one implementation of each command.

---

## Commands

| Command | What it does |
| --- | --- |
| `make up` | **The one command.** Build + start the full stack, wait for health, open the UI. |
| `make down` | Stop everything and remove volumes (full reset). |
| `make dev` | Hot reload: Spring DevTools + Vite HMR against the same Cassandra. |
| `make test` | Unit + integration (Testcontainers) + frontend, with coverage gates. |
| `make e2e` | Playwright against a freshly seeded stack, headless. |
| `make e2e-ui` | Same, headed (Playwright UI on `:8130`), for debugging. |
| `make bench` | The §11.2 performance benchmarks; appends to `bench/trend.csv`. |
| `make verify` | **The pre-push gate** — exactly what CI runs per PR (contract · lint · arch · unit · integration · e2e · security). |
| `make seed` | Reload demo data (incl. the vector table for ANN). |

Supporting targets: `make contract` · `make db` · `make cql` · `make logs` · `make ps` · `make config` ·
`make clean` · `make restart` · `make lint` · `make arch` · `make unit` · `make integration` ·
`make security` · `make mutation` · `make compat` · `make show-contracts` · `make help`.

`make db`, `make seed`, `make cql`, `make config`, `make contract` and `make compat` work with **no
backend or frontend present** — useful while those workstreams are still landing.

### Ports

Everything is configurable in `.env`:

| Port | Service |
| --- | --- |
| `8080` | The app (nginx: SPA + `/api` proxied to the backend — one origin) |
| `8081` | Backend published directly (curl / Swagger) |
| `5173` | Vite dev server with HMR (`make dev`) |
| `9042` | Cassandra native protocol |

---

## Build contracts

Other workstreams own `backend/` and `frontend/`. The compose stack builds them by path, so
these are the interfaces they must satisfy. `make show-contracts` prints this section.

**`backend/Dockerfile`** — multi-stage: a `maven:3.9-eclipse-temurin-21` build stage running
`mvn -B package` over the multi-module reactor, then a JRE 21 runtime stage running the
`cassyx-api` fat jar. Requirements:

- listens on **container port 8080**;
- serves `GET /api/health` returning HTTP 200 once ready (this drives the compose healthcheck,
  which everything else waits on — no `sleep` anywhere);
- `curl` must be present in the runtime image (the healthcheck uses it);
- reads its Cassandra coordinates from `CASSANDRA_HOST` / `CASSANDRA_PORT` / `CASSANDRA_DC` /
  `CASSANDRA_USER` / `CASSANDRA_PASSWORD`, its store path from `CASSYX_DATA_DIR` (a mounted
  volume, default `/data`), and the §9 licensing/billing values from the env listed in
  `.env.example`;
- writes job artifacts / export sinks to `/out` (mounted from `./.cassyx-out`) so bulk data never
  round-trips through the browser;
- `backend/pom.xml` is the reactor root, and the following goals must work from it, because
  `make lint`, `make arch`, `make integration`, `make mutation` and `make security` invoke them
  verbatim inside a Maven container:
  `spotless:check`, `checkstyle:check`, `test`, `verify`,
  `test -Dtest='*ArchTest,*ArchitectureTest'` (ArchUnit rules must use one of those two suffixes), `-pl cassyx-core,cassyx-bulk org.pitest:pitest-maven:mutationCoverage`,
  `org.owasp:dependency-check-maven:check -DfailBuildOnCVSS=7`;
- integration tests use Testcontainers and talk to the host daemon through the mounted
  `/var/run/docker.sock`; `TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal` is already set.
- optional: a `bench` Maven profile (`-Pbench -Dbench.rows=N`) emitting
  `BENCH <variant> <metric> <value> <unit>` lines, which `scripts/bench.sh` records.

**`frontend/Dockerfile`** — multi-stage: a Node 22 build stage running `npm ci && npm run build`,
then `nginx:alpine` with the built SPA in `/usr/share/nginx/html`. Requirements:

- listens on **container port 8080**, serves `/healthz`, and proxies `/api` with
  `proxy_buffering off` (SSE job progress + streaming downloads);
- the nginx config is **owned by the frontend workstream** (`frontend/nginx.conf`). Its upstream is
  `cassyx-api:8080`, which `docker-compose.yml` provides as a **network alias** of the backend
  service — so that file never needs to know our compose service names;
- a `dev` build stage running the Vite dev server on `:5173`. `make dev` builds
  `--target dev` and bind-mounts `src/`, `public/`, `index.html` and `vite.config.ts`;
  the dev proxy target comes from `CASSYX_API_PROXY` (set to `http://backend-dev:8080`);
- `frontend/package.json` must provide `dev`, `build`, `lint`, `typecheck`, `test` and `gen:api`
  scripts. `make unit-frontend` runs `npm run test` (must be non-watch, with coverage — 70%
  statement gate); `make lint-frontend` runs `npm run lint && npm run typecheck`;
  `make contract` runs `npm run gen:api`, which must regenerate the typed client from
  `openapi/cassyx-api.yaml` and fail loudly on a bad spec;
- the document title must contain "cassyx" (asserted by the E2E smoke test).

**`openapi/cassyx-api.yaml`** — `openapi: 3.1.1` exactly, every `$ref` resolving, `redocly lint`
clean at zero errors and zero warnings. Enforced by `make contract` (§2.3).

**`e2e/`** — owned here. Playwright 1.49.1, matching `PLAYWRIGHT_IMAGE` in `.env.example`.
Phase 1 workstreams add their journeys under `e2e/tests/`; seed fixtures are listed below.

---

## Seed data

`make seed` applies [`scripts/seed.cql`](scripts/seed.cql) and then
[`scripts/seed.sh`](scripts/seed.sh) generates the volume-scaled parts. Per plan §2.2 the dataset
deliberately exercises the hard paths, in keyspace `cassyx_demo`:

| Table | Exercises |
| --- | --- |
| `users` | `list` / `set` / `map`, UDTs (`address`, `audit_info`), a collection of UDTs, tuples, `blob`, `inet`, `duration`, `varint`, `decimal`, `timeuuid`, **static columns**, null-vs-unset, SAI on a scalar and on collection values |
| `page_counters` | **counters** (the case where `batch.maxBatchStatements` must drop to 1) |
| `app_events` | evenly distributed time series, static column on a partition, SAI on `event_type`, plus a materialized view `app_events_by_type` |
| `sensor_readings` | the **deliberately skewed partition** (`sensor_id='HOT'` holds ~99% of rows) that the §5.2 work-stealing path must absorb |
| `doc_embeddings` | **`vector<float, 1536>` with an SAI index** (`similarity_function: cosine`) + a scalar SAI index for hybrid queries; vectors are clustered around centroids so ANN returns meaningful neighbours |
| `wide_grid` | the **wide ~1000-column table** for the §11.2 grid first-paint benchmark (mixed column types across the width) |
| `e2e_scratch` | a small table that is safe to `TRUNCATE` in E2E |

Every volume is env-configurable, so the same script seeds a benchmark-sized cluster:

```bash
make seed SEED_SCALE=50                 # 50x the default row counts
SEED_WIDE_COLUMNS=2000 make seed        # wider grid
SEED_VECTOR_DIM=768 make seed           # different embedding dimension
```

See the `SEED_*` block in `.env.example` for every knob.

> Cassandra **5.x is required** — `vector<float,N>` and SAI do not exist before it. `seed.sh`
> checks `release_version` and warns loudly if you point it at an older cluster.

---

## CI

`.github/workflows/ci.yml` runs the per-PR job set from §11.1, all jobs in parallel with no
`needs:` between them:

```
contract   lint   arch   unit   integration   e2e   security
```

`contract` runs first and fast: `openapi/cassyx-api.yaml` is the coordination artifact for eight
parallel workstreams, so it is the cheapest signal that they are all still building against the
same API. It asserts `openapi: 3.1.1` exactly, that every `$ref` resolves (reporting the missing
component names, which `redocly` does not), that `redocly lint` reports **zero errors and zero
warnings**, that the frontend's `npm run gen:api` succeeds and produces output, and — once the
backend serves traffic — that live responses do not drift from the schema. Run it locally with
`make contract`.

Each job runs **the same `make` target you run locally** — that is the no-drift rule. A dedicated
`verify-parity` job runs [`scripts/check-verify-parity.sh`](scripts/check-verify-parity.sh), which
diffs the target set invoked by `make verify` against the target set invoked by the workflow and
fails if they diverge. If you need CI to do something new, change the Makefile target, never the
workflow step.

Branch protection should require: `contract`, `lint`, `arch`, `unit`, `integration`, `e2e`, `security`.

`.github/workflows/nightly.yml` runs what is too slow for a PR:

| Job | Detail |
| --- | --- |
| `mutation` | PIT on `cassyx-core` + `cassyx-bulk` only, 70% mutation-score gate |
| `compat` | matrix vs C\* 3.11 / 4.1 / 5.0 / ScyllaDB (DSE 6.8 once registry credentials exist), asserting the §7.1 capability matrix instead of erroring |
| `bench` | §11.2 benchmarks; a row is appended to the committed `bench/trend.csv` so regressions show up as a diff |

Repo secret: **`NVD_API_KEY`** — OWASP Dependency-Check needs it. The NVD feed rate-limits
anonymous clients hard enough that the update usually fails outright, so `make security` prints a
hint and the CI job reads the secret. A key takes about a minute to get:
<https://nvd.nist.gov/developers/request-an-api-key>. Locally: `export NVD_API_KEY=...`.

Secret scanning uses [`.gitleaks.toml`](.gitleaks.toml). Its allowlist is deliberately narrow —
committed `PLACEHOLDER` values (§9.3 requires the examples to be placeholders and nothing else)
plus build output. Do not widen it to whole files.

---

## Troubleshooting

| Symptom | Fix |
| --- | --- |
| `backend/ does not exist yet` | That workstream has not landed. `make db`, `make seed`, `make cql`, `make config` still work. |
| Port 8080 or 9042 already in use | Change `CASSYX_WEB_PORT` / `CASSANDRA_PORT` in `.env`. |
| Cassandra never becomes healthy | It needs ~60s and ~2 GB on first boot. `make logs`, or raise `CASSANDRA_MAX_HEAP`. Docker Desktop memory below 4 GB will not do it. |
| `vector<float,1536>` DDL fails during seed | You are not on Cassandra 5.x. Check `CASSANDRA_IMAGE` in `.env`. |
| Testcontainers cannot reach Docker | The Maven container mounts `/var/run/docker.sock`; on non-standard daemons set `DOCKER_HOST` / `TESTCONTAINERS_HOST_OVERRIDE`. |
| Stale data after a schema change | `make down && make up` (removes volumes), or just `make seed`. |

---

## Repository layout

```
cassyx/
├── Makefile               the one entry point (§2.2)
├── cassyx                 Windows/WSL parity wrapper
├── docker-compose.yml     cassandra · app · dev · tools · e2e · seed profiles
├── .env.example           committed defaults, copied to .env on first run
├── docs/plan.md           the authoritative spec
├── openapi/               the API contract (written first)
├── backend/               Maven multi-module, Java 21, Spring Boot 3.5
├── frontend/              Vite + React 19 + TypeScript
├── e2e/                   Playwright harness + specs
├── scripts/               seed, health, preflight, bench, compat helpers
├── bench/trend.csv        committed benchmark trend (nightly appends)
└── .github/workflows/     ci.yml (per-PR) · nightly.yml
```

---

## Licence

Cassyx is source-available under the **Elastic License 2.0** (ELv2). See [`LICENSE`](LICENSE).

You are free to read the source, self-host it, and modify it for your own use. What ELv2 does not
allow is:

- offering Cassyx to third parties as a hosted or managed service;
- moving, changing, disabling or circumventing the licence-key functionality described in
  `docs/plan.md` §9, or removing/obscuring any feature it protects;
- altering or removing licensing or copyright notices.

In short: **bypassing the licence key, other than through the sanctioned mechanism documented in
`docs/plan.md` §9.2, is a licence violation.**

Worth being clear about what this does and does not buy: ELv2 is a legal control, not a technical
one. It does not make a self-hosted binary tamper-proof. What it does is make circumvention an
actionable breach rather than merely something we would prefer you did not do.

Cassyx is a one-time paid purchase, not a subscription. See `docs/plan.md` §9 for how the licence
key and checkout work; purchase and activation happen through the in-app pricing screen once
billing is enabled.
