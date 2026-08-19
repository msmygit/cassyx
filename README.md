# cassyx

A self-hosted, Dockerized CQL IDE, data manager and bulk data mover for Apache Cassandra, DSE,
Astra DB, Amazon Keyspaces and ScyllaDB — vector/SAI/ANN native.

The authoritative specification is [`docs/plan.md`](docs/plan.md). This README covers the
developer experience (§2.2) and CI (§11.1).

**Installing cassyx?** Jump to [Install a release](#install-a-release). Everything after that
section is for people building cassyx, not running it.

---

## Install a release

**Requirements: Docker. That is the entire list** - no source checkout, no Make, no Java, Node or
Maven. Released images are published to GHCR for **linux/amd64 and linux/arm64**, so Apple Silicon
runs natively rather than under emulation.

```bash
mkdir cassyx && cd cassyx

curl -fsSLO https://raw.githubusercontent.com/msmygit/cassyx/main/docker-compose.release.yml
curl -fsSL  https://raw.githubusercontent.com/msmygit/cassyx/main/.env.release.example -o .env

# Required: the key that encrypts your stored cluster credentials (§3).
# There is no default on purpose (a shipped one would be public knowledge).
echo "CASSYX_SECRET_KEY=$(openssl rand -base64 32)" >> .env

# Optional but recommended: pin a version instead of riding `latest`.
echo "CASSYX_VERSION=1.0.0" >> .env

docker compose -f docker-compose.release.yml up -d
```

Open <http://localhost:8080>, and paste your licence key into the activation screen (or put it in
`.env` as `CASSYX_LICENSE_KEY` and restart).

| | |
| --- | --- |
| Images | `ghcr.io/msmygit/cassyx-backend`, `ghcr.io/msmygit/cassyx-frontend` |
| Tags | `1.2.3`, `1.2`, `1`, `latest`. `latest` never moves to a prerelease (`v1.2.3-rc1`). |
| Update | `docker compose -f docker-compose.release.yml pull && docker compose -f docker-compose.release.yml up -d` |
| Stop | `docker compose -f docker-compose.release.yml down` (add `--volumes` to also erase your saved connections) |
| Logs | `docker compose -f docker-compose.release.yml logs -f` |

**No Cassandra is bundled, deliberately.** cassyx manages the clusters you already have: register
them in the UI (Cassandra, DSE, Astra, Keyspaces, ScyllaDB). Your data never moves into cassyx; the
`cassyx-data` volume holds only your connections, saved scripts, history, jobs and licence.

Every setting is documented in [`.env.release.example`](.env.release.example). The two that matter:
`CASSYX_SECRET_KEY` (required; changing it makes saved connections undecryptable) and
`CASSYX_LICENSE_KEY`.

`docker-compose.release.yml` pulls published images and contains no `build:` stanzas. The
[`docker-compose.yml`](docker-compose.yml) in this repo is the *development* stack and builds
everything from source; do not use it to install.

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
| `make release-local` | Dry-run a release: build both images, run `docker-compose.release.yml`, smoke it. No push. |
| `make release-down` | Stop the release stack started by `make release-local` and remove its volumes. |

### Supporting targets

The individual gates `make verify` composes, plus the day-to-day housekeeping. Run one directly
when you want its signal on its own rather than waiting for the whole pre-push gate.

**Gates** (each is also a CI job, running this exact target):

| Command | What it is for |
| --- | --- |
| `make contract` | The API contract gate, and the one that runs first because it is cheapest: `redocly lint` at zero errors *and* zero warnings, every `$ref` resolving, `npm run gen:api` producing clean TypeScript, and live responses checked against the schema for drift (§2.3). |
| `make lint` | Everything style- and syntax-level at once: `actionlint`, the gitignore guard, `spotless:check` + `checkstyle:check`, `eslint`, `tsc --noEmit`. |
| `make lint-workflows` | Just `actionlint`. Worth knowing separately: an invalid workflow file does not fail loudly, it makes every job die at startup, so CI silently stops running while still looking like it ran. |
| `make arch` | ArchUnit rules enforcing the §2.1 modularity contract: no Spring below `cassyx-api`, no module reaching into a sibling's implementation package. |
| `make unit` | Backend unit tests only. No containers, no network, so it is the fastest useful feedback loop. |
| `make unit-frontend` | `vitest --coverage` against the 70%-statements gate (§11.1). |
| `make integration` | `mvn verify`: the integration suite against a shared Testcontainers Cassandra 5.x, plus the per-module JaCoCo coverage gates. |
| `make smoke` | Boots the real stack and asserts the ungated endpoints actually answer. Catches the class of defect every unit test passes and that kills the app on first boot. |
| `make security` | The per-commit half: `gitleaks` secret scan + `npm audit`. |
| `make cve-scan` | OWASP Dependency-Check over the reactor (§2 CVE pins). Weekly and on dependency changes, not per commit: between two commits that do not touch a manifest, only the NVD feed can change the verdict. Needs `NVD_API_KEY`. |
| `make mutation` | PIT mutation testing on `cassyx-core` + `cassyx-bulk` only, 70% score gate. Nightly: too slow for a PR, and these are the modules where line coverage most easily lies. |
| `make compat` | Compatibility smoke across the §7.1 target matrix (C\* 3.11 / 4.1 / 5.0 / ScyllaDB). Nightly. |

**Database and stack control:**

| Command | What it is for |
| --- | --- |
| `make db` | Start *only* Cassandra 5.x and wait for a real health check. Useful when you want a cluster to poke at and nothing else. |
| `make cql` | Open an interactive `cqlsh` shell against that dev cluster. |
| `make ps` | Show what is running. |
| `make logs` | Follow logs for every running service. |
| `make open` | Open the app URL in a browser. |
| `make restart` | `down` then `up`, i.e. a full reset including volumes. |
| `make clean` | Stop containers but **keep** volumes, so seeded data and the H2 store survive. The gentler `make down`. |
| `make nuke` | `down` plus a prune of dangling cassyx images. |

**Release and introspection:**

| Command | What it is for |
| --- | --- |
| `make release-version` | Print the reactor version. With `TAG=v1.2.3`, assert the tag matches `backend/pom.xml` (the same guard the release workflow runs first). |
| `make config` | Validate `docker-compose.yml` across every profile without starting anything. |
| `make show-contracts` | Print the [Build contracts](#build-contracts) section, i.e. the interface other workstreams' `backend/` and `frontend/` must satisfy. |
| `make help` | List every target with its one-line description. |

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

## Licensing (§9.2)

How the licence *key* works. For the terms cassyx is distributed under, see
[Licence](#licence) at the end of this file.

cassyx is one paid tier: one payment unlocks everything. Two things unlock an instance without
paying, and only one of them works in a published build.

**Site licence:** the supported way. A `site` licence is an ordinary Ed25519-signed key with
`edition: "site"`: unlimited seats, normally perpetual, verified entirely offline by the same code
as a paid key. It is issued **free on request** for CI, evaluation and enterprise self-hosting (we
can also issue a time-boxed one, `expires` and `scope` still apply if present). Set it and nothing
else:

```bash
CASSYX_LICENSE_KEY=<the signed key>
CASSYX_LICENSE_PUBLIC_KEY=<the shipped public key>
```

The UI badges a site licence as granted; it is not the `unlicensed-bypass` state and carries no
warning banner.

**`CASSYX_LICENSE_ENFORCE=false`:** development builds only. The flag still fully unlocks a build
made with the `dev` Maven profile, which is what `make up`, `make dev` and CI build, so the
developer experience is unchanged. Published images are built with the `release` profile
(`backend/Dockerfile` defaults to it), which bakes `cassyx.license.bypass-allowed=false`. There the
flag is **ignored**: enforcement stays on, `GET /api/license` reports `enforce: true` /
`bypass: false`, and a startup WARN names `CASSYX_LICENSE_ENFORCE` so nobody is left wondering why
their flag did nothing.

To build an unlocked image deliberately: `CASSYX_BYPASS_PROFILE=dev make up` (already the default
in `.env.example`), or `docker build --build-arg CASSYX_BYPASS_PROFILE=dev backend/`.

Being honest about what this buys: it raises the cost of a casual bypass from "read the README" to
"patch and rebuild the jar". Self-hosted software the customer runs, and can recompile, is not
tamper-proof, no scheme short of a hosted service is. What it removes is the *supported,
documented* free unlock, and it replaces it with a credential we can issue, scope and time-box.

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

## Cutting a release (maintainers)

`.github/workflows/release.yml` fires on a pushed `v*` tag and does, in order: **guard → build →
boot → smoke → push → GitHub Release**. Nothing reaches GHCR that has not been started and
answered a request first, so a broken image is a failed workflow rather than something to yank.

```bash
# 1. Bump the reactor version. The tag must match it EXACTLY (see below).
#    backend/pom.xml <version>, plus every module's <parent><version>.
$EDITOR backend/pom.xml

# 2. Prove it locally: builds both images, runs docker-compose.release.yml, smokes it.
#    Same sequence the workflow runs, minus the push.
make release-local
make release-down

# 3. Merge to main via PR and let CI go green there.

# 4. Tag the merge commit and push.
git switch main && git pull
git tag -a v1.2.3 -m "cassyx 1.2.3"
git push origin v1.2.3
```

Watch the `release` workflow. Prereleases are just semver: `v1.2.3-rc1` publishes `1.2.3-rc1`
only; the rolling `1.2`, `1` and `latest` tags stay on the last stable release, and the GitHub
Release is flagged as a prerelease.

`workflow_dispatch` on the same workflow is a **dry run**: it builds both architectures, boots the
release stack and smokes it, then pushes nothing. Use it to test a change to the pipeline without
spending a version number.

**The tag must equal `<version>` in `backend/pom.xml`.** The `guard` job enforces it via
[`scripts/release-version.sh`](scripts/release-version.sh) and refuses to build otherwise. This is
not tidiness: the reactor version is what the running app reports from `/api/health` **and what
licence scope is checked against** (§9.5). Tagging `v2.0.0` over a pom that still says `1.0.0`
would publish an image that believes it is v1, so every customer's licence gate decides against
the wrong major. Check it any time with `make release-version TAG=v1.2.3`.

Published images are built with `--build-arg CASSYX_BYPASS_PROFILE=release`, which bakes
`cassyx.license.bypass-allowed=false` so a release image cannot be unlocked by setting
`CASSYX_LICENSE_ENFORCE=false` (§9.2). That build arg ships with the `feat/site-licence` branch;
until it merges `backend/Dockerfile` declares no such `ARG` and buildx silently ignores it, which
is expected and forward-compatible.

Prerequisites, one time only: GHCR publishing uses the built-in `GITHUB_TOKEN` (the workflow
requests `packages: write`), so there is no registry secret to manage. After the first release,
make the two packages public in the repo's Packages settings, or customers get a 401 on `pull`.

> **Known blocker before the first real tag.** Nothing in the build injects the reactor version
> into the running app: `HealthController` and `LicenseController` both read
> `${cassyx.version:0.1.0-SNAPSHOT}`, that property is defined nowhere, and the jar manifest
> carries no `Implementation-Version`, so the hardcoded default always wins. `/api/health` on a
> `1.0.0` build still reports `0.1.0-SNAPSHOT`, and §9.5 licence scope is derived from that string
> (parsing to major 0, i.e. unscoped). The release smoke check fails on this deliberately. Fix in
> `backend/pom.xml`: enable resource filtering and set `cassyx.version: @project.version@` in
> `application.yml`, or bind the `spring-boot-maven-plugin` `build-info` goal.

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
| `set CASSYX_SECRET_KEY in .env` on the release stack | Required, no default. `echo "CASSYX_SECRET_KEY=$(openssl rand -base64 32)" >> .env`. |
| `denied` / 401 pulling `ghcr.io/msmygit/cassyx-*` | The packages are private. Maintainers: make them public in the repo's Packages settings. |
| `release` workflow fails at `guard` | The tag and `backend/pom.xml` disagree. Fix the pom, re-tag; see [Cutting a release](#cutting-a-release-maintainers). |

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

The terms cassyx is distributed under. For how the licence *key* works, and how to run an instance
unlocked, see [Licensing (§9.2)](#licensing-92) above.

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
