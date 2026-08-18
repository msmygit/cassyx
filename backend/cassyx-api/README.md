# cassyx-api

The Spring Boot application: REST/SSE adapters and bean wiring. **The only module allowed to depend
on Spring** (plan §2.1) — it supplies `@Bean` wiring for the plain-Java modules below it, and every
bean is obtained from a module's `…api` factory, never from an `…impl` class.

## Run

```bash
../mvnw -pl cassyx-api -am spring-boot:run
curl localhost:8080/api/health          # {"status":"UP"} - ungated, per §9.1
```

Fat jar: `../mvnw package` → `target/cassyx-api-<version>.jar`.

## Configuration (`application.yml`)

* **Virtual threads on** (`spring.threads.virtual.enabled=true`) — Java 21, per §2/§5.1.
* **H2 in file mode** at `${CASSYX_DB_PATH:./data/cassyx}`: connections, saved scripts, history,
  jobs and the license. Put it on a mounted volume in Docker.
* `cassyx.license.*` (§9.2) and `cassyx.billing.*` (§9.3) — every value a placeholder, real values
  from the environment. `CASSYX_LICENSE_ENFORCE=false` unlocks everything and logs a startup WARN.
* `cassyx.scb.path-root` (`CASSYX_SCB_PATH_ROOT`, default `/etc/cassyx/scb`) — the allow-list root
  for PATH-mode secure connect bundles.
* Spring Boot's Cassandra auto-configuration is **excluded**: cassyx owns session lifecycle through
  its own registry, one `CqlSession` per connection, and must boot with no cluster reachable.

## Flyway (`src/main/resources/db/migration`)

`V1__baseline.sql` creates `cassyx_connection` (AES-256-GCM ciphertext + per-value GCM nonce columns
for every credential, plus the cached secure connect bundle), `cassyx_script_folder`,
`cassyx_saved_script`, `cassyx_query_history`, `cassyx_job`, `cassyx_job_template`, `cassyx_license`
and `cassyx_billing_event` (webhook idempotency). Migrations are append-only: add `V2__*.sql`, never
edit `V1`.

## Tests

* `ModularityArchitectureTest` — the §2.1 contract, enforced (see the backend README).
* `ApplicationContextSmokeTest` — boots the context, proves Flyway applies and every module wires.
* `SharedCassandraIT` — the reference pattern for Phase 1: extend
  `io.cassyx.core.testsupport.IntegrationTestBase`, use the ONE shared Cassandra 5.x container.
  Run with `-Dcassyx.it=true` (Failsafe sets it).
