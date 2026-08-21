# cassyx maintainer guide

Everything about **building** cassyx, as opposed to running it. If you are installing a release,
you want [`README.md`](../README.md) instead; nothing on this page is needed to use the product.

Two neighbouring documents, and the split between them:

- [`docs/plan.md`](plan.md) is the authoritative specification. When this page and the plan
  disagree, the plan wins and this page is stale.
- [`docs/sell-next-steps.md`](sell-next-steps.md) is the **commercial** runbook: accounts, keys,
  legal documents, end-to-end payment testing. This page is the **mechanical** one. The release
  checklist below cross-references it rather than repeating it.

---

## Quick start

**Requirements: Docker and Make. That is the entire list.** No local Java, Node or Maven; every
build, test and benchmark runs inside a container.

```bash
git clone <repo> && cd cassyx
make up
```

`make up` copies `.env.example` to `.env` on first run, starts Cassandra 5.x, waits for a **real**
health check (`cqlsh -e "describe keyspaces"`, never a `sleep`), seeds the demo dataset, builds and
starts the backend and frontend, waits for both to report healthy, and opens
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
| `make verify` | **The pre-push gate**, exactly what CI runs per PR (contract · lint · arch · unit · integration · e2e · security). |
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
backend or frontend present**, which is useful while those workstreams are still landing.

### Ports

Everything is configurable in `.env`:

| Port | Service |
| --- | --- |
| `8080` | The app (nginx: SPA + `/api` proxied to the backend, one origin) |
| `8081` | Backend published directly (curl / Swagger) |
| `5173` | Vite dev server with HMR (`make dev`) |
| `9042` | Cassandra native protocol |

---

## Build contracts

Other workstreams own `backend/` and `frontend/`. The compose stack builds them by path, so
these are the interfaces they must satisfy. `make show-contracts` prints this section.

**`backend/Dockerfile`** - multi-stage: a `maven:3.9-eclipse-temurin-21` build stage running
`mvn -B package` over the multi-module reactor, then a JRE 21 runtime stage running the
`cassyx-api` fat jar. Requirements:

- listens on **container port 8080**;
- serves `GET /api/health` returning HTTP 200 once ready (this drives the compose healthcheck,
  which everything else waits on, so there is no `sleep` anywhere);
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

**`frontend/Dockerfile`** - multi-stage: a Node 22 build stage running `npm ci && npm run build`,
then `nginx:alpine` with the built SPA in `/usr/share/nginx/html`. Requirements:

- listens on **container port 8080**, serves `/healthz`, and proxies `/api` with
  `proxy_buffering off` (SSE job progress + streaming downloads);
- the nginx config is **owned by the frontend workstream** (`frontend/nginx.conf`). Its upstream is
  `cassyx-api:8080`, which `docker-compose.yml` provides as a **network alias** of the backend
  service, so that file never needs to know our compose service names;
- a `dev` build stage running the Vite dev server on `:5173`. `make dev` builds
  `--target dev` and bind-mounts `src/`, `public/`, `index.html` and `vite.config.ts`;
  the dev proxy target comes from `CASSYX_API_PROXY` (set to `http://backend-dev:8080`);
- `frontend/package.json` must provide `dev`, `build`, `lint`, `typecheck`, `test` and `gen:api`
  scripts. `make unit-frontend` runs `npm run test` (must be non-watch, with coverage, 70%
  statement gate); `make lint-frontend` runs `npm run lint && npm run typecheck`;
  `make contract` runs `npm run gen:api`, which must regenerate the typed client from
  `openapi/cassyx-api.yaml` and fail loudly on a bad spec;
- the document title must contain "cassyx" (asserted by the E2E smoke test).

**`openapi/cassyx-api.yaml`** - `openapi: 3.1.1` exactly, every `$ref` resolving, `redocly lint`
clean at zero errors and zero warnings. Enforced by `make contract` (§2.3).

**`e2e/`** - owned here. Playwright 1.49.1, matching `PLAYWRIGHT_IMAGE` in `.env.example`.
Phase 1 workstreams add their journeys under `e2e/tests/`; seed fixtures are listed below.

---

## Seed data

`make seed` applies [`scripts/seed.cql`](../scripts/seed.cql) and then
[`scripts/seed.sh`](../scripts/seed.sh) generates the volume-scaled parts. Per plan §2.2 the
dataset deliberately exercises the hard paths, in keyspace `cassyx_demo`:

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

> Cassandra **5.x is required**: `vector<float,N>` and SAI do not exist before it. `seed.sh`
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
warnings**, that the frontend's `npm run gen:api` succeeds and produces output, and, once the
backend serves traffic, that live responses do not drift from the schema. Run it locally with
`make contract`.

Each job runs **the same `make` target you run locally**, which is the no-drift rule. A dedicated
`verify-parity` job runs [`scripts/check-verify-parity.sh`](../scripts/check-verify-parity.sh),
which diffs the target set invoked by `make verify` against the target set invoked by the workflow
and fails if they diverge. If you need CI to do something new, change the Makefile target, never
the workflow step.

Branch protection should require: `contract`, `lint`, `arch`, `unit`, `integration`, `e2e`, `security`.

`.github/workflows/nightly.yml` runs what is too slow for a PR:

| Job | Detail |
| --- | --- |
| `mutation` | PIT on `cassyx-core` + `cassyx-bulk` only, 70% mutation-score gate |
| `compat` | matrix vs C\* 3.11 / 4.1 / 5.0 / ScyllaDB (DSE 6.8 once registry credentials exist), asserting the §7.1 capability matrix instead of erroring |
| `bench` | §11.2 benchmarks; a row is appended to the committed `bench/trend.csv` so regressions show up as a diff |

Repo secret: **`NVD_API_KEY`**, which OWASP Dependency-Check needs. The NVD feed rate-limits
anonymous clients hard enough that the update usually fails outright, so `make security` prints a
hint and the CI job reads the secret. A key takes about a minute to get:
<https://nvd.nist.gov/developers/request-an-api-key>. Locally: `export NVD_API_KEY=...`.

Secret scanning uses [`.gitleaks.toml`](../.gitleaks.toml). Its allowlist is deliberately narrow:
committed `PLACEHOLDER` values (§9.3 requires the examples to be placeholders and nothing else)
plus build output. Do not widen it to whole files.

---

## Licence enforcement: how the build decides (§9.2)

This is maintainer-only mechanics. Customer-facing licensing (activating a key, site licences,
where to buy) lives in [README → Licensing and activation](../README.md#licensing-and-activation),
and deliberately does not describe any of what follows.

Two properties decide whether an instance is gated:

```yaml
cassyx:
  license:
    enforce:        ${CASSYX_LICENSE_ENFORCE:true}   # runtime env var
    bypass-allowed: @cassyx.license.bypass.allowed@  # BUILD-time, filtered by Maven
```

`bypass-allowed` is baked in at package time by a profile in `backend/cassyx-api/pom.xml`: `dev`
(active by default, so `make up`, `make dev` and CI are unaffected) filters it to `true`, `release`
to `false`. It is deliberately **not** an env var, because a runtime switch guarding a runtime
switch guards nothing.

| `enforce` | `bypass-allowed` | Result |
| --- | --- | --- |
| `true` | either | Normal verification. A `site` key unlocks it, no flag involved. |
| `false` | `true` (dev build) | Bypass granted: synthetic licence, `edition: unlicensed-bypass`, `state: BYPASS`, banner visible. |
| `false` | `false` (release build) | Bypass **refused**: the flag is ignored, enforcement stays on, and a startup WARN names `CASSYX_LICENSE_ENFORCE` so nobody wonders why their flag did nothing. |

`backend/Dockerfile` declares `ARG CASSYX_BYPASS_PROFILE=release`, and `.github/workflows/release.yml`
passes `--build-arg CASSYX_BYPASS_PROFILE=release` explicitly on both the verification build and
the multi-arch push. Explicitly, rather than relying on the Dockerfile default, because a default
that can silently change is not a control. `.env.example` sets `CASSYX_BYPASS_PROFILE=dev` so the
development stack builds an unlocked image; to build one by hand,
`docker build --build-arg CASSYX_BYPASS_PROFILE=dev backend/`.

Verified on the published 1.0.0 image: a release build with `CASSYX_LICENSE_ENFORCE=false` set
reports `enforce: true` / `bypass: false` from `GET /api/license` and still answers `402` on gated
endpoints. `docs/sell-next-steps.md` §3.1 is the two-command check; run it on every release.

Be honest about what this buys. It raises the cost of a casual bypass from "read the README" to
"patch and rebuild the jar". Self-hosted software the customer runs, and can recompile, is not
tamper-proof; no scheme short of a hosted service is. What it removes is the *supported,
documented* free unlock, and it replaces it with a credential we can issue, scope and time-box:
the free `site` licence described in `docs/sell-next-steps.md` §5.4.

---

## Cutting a release

`.github/workflows/release.yml` fires on a pushed `v*` tag and does, in order: **guard → build →
boot → smoke → push → GitHub Release**. Nothing reaches GHCR that has not been started and
answered a request first, so a broken image is a failed workflow rather than something to yank.

`workflow_dispatch` on the same workflow is a **dry run**: it builds both architectures, boots the
release stack and smokes it, then pushes nothing. Use it to test a change to the pipeline without
spending a version number.

Prereleases are just semver: `v1.2.3-rc1` publishes `1.2.3-rc1` only; the rolling `1.2`, `1` and
`latest` tags stay on the last stable release, and the GitHub Release is flagged as a prerelease.

**The tag must equal `<version>` in `backend/pom.xml`.** The `guard` job enforces it via
[`scripts/release-version.sh`](../scripts/release-version.sh) and refuses to build otherwise. This
is not tidiness: the reactor version is what the running app reports from `/api/health` **and what
licence scope is checked against** (§9.5). Tagging `v2.0.0` over a pom that still says `1.0.0`
would publish an image that believes it is v1, so every customer's licence gate decides against the
wrong major. Check it any time with `make release-version TAG=v1.2.3`.

### The checklist

The commercial half of a release (pricing decisions, GHCR visibility, the end-to-end payment test)
is in [`docs/sell-next-steps.md`](sell-next-steps.md) §4 and is not repeated here.

Steps 1 to 4 are workflows you dispatch from the **Actions** tab. Nothing needs a terminal until
the verification in step 6, which deliberately does.

1. **Actions → "Prepare release" → Run workflow**, entering a bare version (`1.2.3`, no leading
   `v`; the tag carries the `v`, the pom does not).

   It sets the version across **all eight** poms, verifies they agree, confirms
   `scripts/release-version.sh` reports it, proves the tree still builds, and opens a PR.

   Eight, not seven: `licensing/` sits outside the reactor behind a profile and carries its own
   `<parent><version>`, so a plain `versions:set` in `backend/` misses it. That is not
   hypothetical - it is what happened at 1.0.0, where `licensing/pom.xml` stayed at
   `0.1.0-SNAPSHOT` and the module only built because a stale parent was cached locally.

   Bumping the *major* is a pricing decision rather than a version bump: see sell-next-steps §4.

2. **Review and merge that PR.** The bump goes through review like any other change; the workflow
   never pushes to `main`.

3. **Actions → "Tag release" → Run workflow**, same version plus an annotation message.

   It refuses unless it is on `main` and the pom already matches, checks the tag is free locally
   and remotely, then **builds the images, boots the release stack and smokes it - and only tags if
   that passes.**

   That ordering is the point of the workflow. A tag is public the moment it is pushed, and a
   release that fails afterwards leaves a tag pointing at nothing. Verifying first means the tag is
   only ever created for a build already known to work.

4. **`release.yml` starts automatically** on the tag push and does the real multi-arch build,
   smoke and publish. This requires `RELEASE_TOKEN`; see below, because without it this step
   silently does not happen.

5. **Watch the `release` workflow.** A failure before the push step means nothing was published,
   which is the intended outcome: fix, delete the tag, re-tag.
6. **Verify the published image as an anonymous customer.** Not from your own machine's cached
   credentials:

   ```bash
   docker logout ghcr.io
   docker pull ghcr.io/msmygit/cassyx-backend:1.2.3    # must succeed
   ```

7. **Confirm the packages are public.** Package visibility is separate from repository visibility,
   and a private package gives every customer `denied` on their first command. sell-next-steps §1.1
   has the exact clicks. This is a one-time step after the *first* release, but re-verify it with
   the anonymous pull above every time.
8. **Install it as a customer would and confirm the activation screen appears.** Follow
   [README → Install a release](../README.md#install-a-release) verbatim in an empty directory, open
   <http://localhost:8080>, and confirm you are asked for a licence key. See the warning below
   before deciding this step is optional.
9. **Confirm a release image refuses the bypass flag**: sell-next-steps §3.1.

> **The failure this checklist exists for.** 1.0.0 was published unable to verify *any* licence:
> `CASSYX_LICENSE_PUBLIC_KEY` defaulted to `PLACEHOLDER`, so the app reported "This server is not
> configured for licensing" instead of an activation screen, and no key anybody bought could have
> worked. Every other check passed. The version guard passed, the smoke check passed, the images
> booted and served. A `CASSYX_SMOKE_EXPECT_LICENSABLE` assertion has since been written to fail
> the release on exactly this (see the `fix/bake-public-key` branch; confirm it is on `main` and
> wired into the release smoke step before relying on it). Either way, step 8 stays manual: **look
> at the screen a paying customer will see, and check it asks for a key.**

---

### `RELEASE_TOKEN` - without it, tagging looks like it worked and nothing releases

**GitHub does not start workflows from events created with `GITHUB_TOKEN`.** It is a recursion
guard and it cannot be turned off. So a tag pushed by `tag-release` using the default token lands
normally, the run goes green, and `release.yml` never fires: no images, no GitHub Release, and
nothing anywhere reporting a failure.

Both release workflows therefore prefer a `RELEASE_TOKEN` secret and fall back to `GITHUB_TOKEN`
with a loud warning in the log and in the job summary.

**Setting it up** (once, five minutes):

1. GitHub → your avatar → Settings → Developer settings → Personal access tokens →
   **Fine-grained tokens** → Generate new token.
2. Repository access: **Only select repositories** → this repository.
3. Repository permissions: **Contents: Read and write** (this is what pushes tags and branches) and
   **Pull requests: Read and write** (so `prepare-release` can open its PR).
4. Set an expiry you will actually notice, and put a calendar reminder on it. An expired
   `RELEASE_TOKEN` degrades silently to the `GITHUB_TOKEN` path.
5. Copy the token, then in the repository: Settings → Secrets and variables → **Actions** →
   New repository secret, named exactly **`RELEASE_TOKEN`**.

**If it is missing:** the tag is still pushed (refusing would be worse), but you must re-push it
from a machine using your own credentials to produce an event GitHub will act on:

```bash
git fetch --tags
git push --delete origin v1.0.1
git push origin v1.0.1
```

Safe at that point precisely because nothing has been published yet: same ref, same commit.

Do **not** reach for `gh workflow run release.yml --ref v1.0.1` instead. A `workflow_dispatch` of
`release` is a **dry run by design** (it sets `PUSH=false`), so it builds, smokes, goes green and
publishes nothing - the most convincing way possible to not release.

The same suppression applies to the PR `prepare-release` opens: without `RELEASE_TOKEN`, `ci` will
not run on it. Close and reopen the PR in the UI, or push any commit to its branch, to make the
checks appear.

---

## Troubleshooting the development stack

Customer-facing symptoms are in [README → Troubleshooting](../README.md#troubleshooting).

| Symptom | Fix |
| --- | --- |
| `backend/ does not exist yet` | That workstream has not landed. `make db`, `make seed`, `make cql`, `make config` still work. |
| Port 8080 or 9042 already in use | Change `CASSYX_WEB_PORT` / `CASSANDRA_PORT` in `.env`. |
| Cassandra never becomes healthy | It needs ~60s and ~2 GB on first boot. `make logs`, or raise `CASSANDRA_MAX_HEAP`. Docker Desktop memory below 4 GB will not do it. |
| `vector<float,1536>` DDL fails during seed | You are not on Cassandra 5.x. Check `CASSANDRA_IMAGE` in `.env`. |
| Testcontainers cannot reach Docker | The Maven container mounts `/var/run/docker.sock`; on non-standard daemons set `DOCKER_HOST` / `TESTCONTAINERS_HOST_OVERRIDE`. |
| Stale data after a schema change | `make down && make up` (removes volumes), or just `make seed`. |
| `release` workflow fails at `guard` | The tag and `backend/pom.xml` disagree. Fix the pom, re-tag; see [Cutting a release](#cutting-a-release). |
| A dev build behaves as licensed and you did not expect it | `.env.example` sets `CASSYX_BYPASS_PROFILE=dev`. That is the intended developer experience; see [Licence enforcement](#licence-enforcement-how-the-build-decides-92). |

---

## Repository layout

```
cassyx/
├── Makefile               the one entry point (§2.2)
├── cassyx                 Windows/WSL parity wrapper
├── docker-compose.yml     cassandra · app · dev · tools · e2e · seed profiles
├── .env.example           committed defaults, copied to .env on first run
├── docs/plan.md           the authoritative spec
├── docs/maintainers.md    this file
├── docs/sell-next-steps.md the commercial runbook
├── openapi/               the API contract (written first)
├── backend/               Maven multi-module, Java 21, Spring Boot 3.5
├── frontend/              Vite + React 19 + TypeScript
├── e2e/                   Playwright harness + specs
├── licensing/             the private minting service (never shipped to customers)
├── scripts/               seed, health, preflight, bench, compat helpers
├── bench/trend.csv        committed benchmark trend (nightly appends)
└── .github/workflows/     ci.yml (per-PR) · nightly.yml · release.yml
```
