# cassyx

A self-hosted, Dockerized CQL IDE, data manager and bulk data mover for Apache Cassandra, DSE,
Astra DB, Amazon Keyspaces and ScyllaDB. Vector, SAI and ANN native.

cassyx runs entirely on infrastructure you control. Your data never moves into it, and it never
calls home: there is no telemetry, no usage reporting, and licence verification is offline, so it
works in an air-gapped network.

- **Install it:** [Install a release](#install-a-release). Docker is the only requirement.
- **Buy or activate a licence:** [Licensing and activation](#licensing-and-activation).
- **Building cassyx from source?** That is [`docs/maintainers.md`](docs/maintainers.md), not this
  file.

---

## Install a release

**Requirements: Docker. That is the entire list** - no source checkout, no Make, no Java, Node or
Maven. Released images are published to GHCR for **linux/amd64 and linux/arm64**, so Apple Silicon
runs natively rather than under emulation.

```bash
mkdir cassyx && cd cassyx

curl -fsSLO https://raw.githubusercontent.com/msmygit/cassyx/main/docker-compose.release.yml
curl -fsSL  https://raw.githubusercontent.com/msmygit/cassyx/main/.env.release.example -o .env

# Required: the key that encrypts your stored cluster credentials.
# There is no default on purpose (a shipped one would be public knowledge).
echo "CASSYX_SECRET_KEY=$(openssl rand -base64 32)" >> .env

# Optional but recommended: pin a version instead of riding `latest`.
echo "CASSYX_VERSION=1.0.0" >> .env

docker compose -f docker-compose.release.yml up -d
```

Open <http://localhost:8080> and activate your licence key
([Licensing and activation](#licensing-and-activation)).

| | |
| --- | --- |
| Images | `ghcr.io/msmygit/cassyx-backend`, `ghcr.io/msmygit/cassyx-frontend` |
| Tags | `1.2.3`, `1.2`, `1`, `latest`. `latest` never moves to a prerelease (`v1.2.3-rc1`). |
| Update | `docker compose -f docker-compose.release.yml pull && docker compose -f docker-compose.release.yml up -d` |
| Stop | `docker compose -f docker-compose.release.yml down` (add `--volumes` to also erase your saved connections) |
| Logs | `docker compose -f docker-compose.release.yml logs -f` |

Every setting is documented in [`.env.release.example`](.env.release.example). The two that matter:
`CASSYX_SECRET_KEY` (required; changing it makes saved connections undecryptable) and
`CASSYX_LICENSE_KEY`.

`docker-compose.release.yml` pulls published images and contains no `build:` stanzas. The
[`docker-compose.yml`](docker-compose.yml) in this repo is the *development* stack and builds
everything from source; do not use it to install.

### Connect a cluster

**No Cassandra is bundled, deliberately.** cassyx manages the clusters you already have. Add one in
the UI:

| Target | What you provide |
| --- | --- |
| Apache Cassandra 3.11 / 4.x / 5.x | Contact points, port, local datacenter, credentials |
| DataStax Enterprise | The same, plus DSE authentication if enabled |
| **Astra DB** | Your token. cassyx downloads the secure connect bundle for you through the DevOps API, or you can upload the bundle yourself. |
| Amazon Keyspaces | Service-specific credentials and the regional endpoint |
| ScyllaDB | Contact points and credentials |

TLS is supported with your own truststore and keystore. Every credential cassyx stores is encrypted
at rest with AES-256-GCM using `CASSYX_SECRET_KEY`, which only you hold.

The `cassyx-data` volume holds only your connections, saved scripts, history, jobs and licence.
Bulk job output is written to `./.cassyx-out` on the host, so large exports never round-trip
through your browser.

---

## What you get

**CQL workspace**

- A real editor: multi-statement scripts, statement splitting, syntax awareness, and per-statement
  execution against the connection you choose.
- Paged results you can page forward and back through, cancel mid-flight, and trace.
- Query history and saved scripts, kept in your instance.

**Schema and object management**

- Browse and search the whole schema tree: keyspaces, tables, columns, indexes, materialized views,
  user-defined types, functions and aggregates.
- Create, alter and drop them, with DDL generated, previewed and only then executed, so you see the
  statement before it runs.
- Table info, per-table statistics, roles and permissions (grant and revoke).

**Data browsing and editing**

- An editable grid that copes with wide tables (hundreds of columns) and with Cassandra's full type
  system: collections, UDTs, tuples, counters, static columns, `blob`, `duration`, `inet`.
- Edits become CQL statements you can inspect, and cassyx tells you when a row is not safely
  editable rather than guessing.

**Vector, SAI and ANN**

- Discover `vector<float, N>` columns and manage SAI indexes, including the similarity function.
- Run ANN queries and similarity searches from the UI, with hybrid scalar + vector filtering.

**Bulk data movement**

- **Unload** with token-range parallelism, including work-stealing across skewed partitions.
- **Load** from your files, backed by DSBulk where that is the faster engine, chosen automatically.
- **Count** and table statistics as first-class jobs rather than a query you have to babysit.
- Every job reports live progress, streams its logs, can be cancelled, and leaves a downloadable
  artifact. Job templates save the settings you use repeatedly.

**Import, export and migration helpers** (newer, and still growing): file import with a preview
step, JDBC import from a relational source, table and keyspace export, table duplication and
keyspace copy.

---

## Licensing and activation

cassyx is **one paid tier**: one payment unlocks everything, and it is a one-time purchase rather
than a subscription. A licence key is required to use the product.

**Activating your key.** Either paste it into the activation screen at <http://localhost:8080>, or,
for a headless install, put it in `.env` next to `docker-compose.release.yml` and restart:

```bash
CASSYX_LICENSE_KEY=<the key from your purchase email>
```

```bash
docker compose -f docker-compose.release.yml up -d
```

Verification is **offline**. cassyx checks the signature on your key locally and makes no network
call to us, so it works air-gapped and keeps working regardless of what happens to our
infrastructure. Your licence is perpetual for the major version it covers: your installation does
not stop working, and upgrading to a later major version is your choice, never something an expiry
forces.

**Free site licences.** A `site` licence is an ordinary signed key with unlimited seats, granted
(not bypassed) in the UI, and verified by exactly the same code as a paid key. **We issue them free
on request** for:

- **continuous integration**, where a build agent needs a working instance;
- **evaluation**, if a trial key is not the right shape for you;
- **enterprise self-hosting**, where per-seat keys are not how your organisation works.

Ask, and we will issue one. It is the supported way to run cassyx unlocked, and there is no other.

**Trials.** A 14-day trial key is available with no card required, from the in-app pricing screen.

**Buying.** Purchase and activation happen through the in-app pricing screen: it takes you to
Stripe checkout, and your key arrives by email. If you lose it, ask for a re-send using the address
you bought with.

**Getting in touch** about a site licence, a trial, a lost key or a refund: open an issue at
<https://github.com/msmygit/cassyx/issues>, or reply to your purchase email.

---

## Troubleshooting

| Symptom | Fix |
| --- | --- |
| `set CASSYX_SECRET_KEY in .env` on startup | Required, no default. `echo "CASSYX_SECRET_KEY=$(openssl rand -base64 32)" >> .env`, then bring the stack up again. |
| `denied` / 401 pulling `ghcr.io/msmygit/cassyx-*` | The published packages should be public. If this persists, open an issue; it is our misconfiguration, not yours. |
| The app answers `402 Payment Required` | No valid licence key is active. Paste your key into the activation screen, or set `CASSYX_LICENSE_KEY` and restart. |
| "This server is not configured for licensing" | That build cannot verify keys and cannot be activated. Pull a newer image (`CASSYX_VERSION`), and please tell us which version you saw it on. |
| Port 8080 already in use | Change `CASSYX_WEB_PORT` in `.env`. |
| Saved connections stopped decrypting | `CASSYX_SECRET_KEY` changed. Restore the original value; there is no recovery without it, by design. |
| A cluster will not connect | Use **Test connection** on the connection form: it reports the driver's own error. Check contact points, the local datacenter name (it must match exactly) and TLS material. |
| Astra bundle download fails | The token lacks DevOps API permission, or the database is hibernated. Resume it, or upload the secure connect bundle manually. |
| Vector or SAI features are missing on a cluster | They need Cassandra 5.x. cassyx reports each cluster's capabilities rather than failing at query time. |
| Something else | `docker compose -f docker-compose.release.yml logs -f`, then open an issue with the output. |

---

## Licence

Cassyx is source-available under the **Elastic License 2.0** (ELv2). See [`LICENSE`](LICENSE).

You are free to read the source, self-host it, and modify it for your own use. What ELv2 does not
allow is:

- offering Cassyx to third parties as a hosted or managed service;
- moving, changing, disabling or circumventing the licence-key functionality, or removing or
  obscuring any feature it protects;
- altering or removing licensing or copyright notices.

If you need to run cassyx unlocked for CI, evaluation or an enterprise deployment, that is a
[free site licence](#licensing-and-activation), not a workaround. Ask us; the answer is yes.

Worth being clear about what a licence does and does not buy: ELv2 is a legal control, not a
technical one. It does not make self-hosted software tamper-proof. What it does is make
circumvention an actionable breach rather than merely something we would prefer you did not do.

---

## For developers

Building cassyx from source, the Make targets, CI, and the release process are in
[`docs/maintainers.md`](docs/maintainers.md). The authoritative specification is
[`docs/plan.md`](docs/plan.md).
