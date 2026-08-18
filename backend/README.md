# cassyx backend

Maven multi-module build, Java 21. Six modules, one rule: **only `cassyx-api` may depend on Spring.**

| Module | Depends on | Public entry point | Spring? | Coverage gate (§11.1) |
| --- | --- | --- | --- | --- |
| [`cassyx-core`](cassyx-core/README.md) | driver only | `CoreFactory` | no | 85% + mutation |
| [`cassyx-bulk`](cassyx-bulk/README.md) | `cassyx-core` | `BulkFactory` | no | 85% + completeness |
| [`cassyx-vector`](cassyx-vector/README.md) | `cassyx-core` | `VectorFactory` | no | 80% |
| [`cassyx-migrate`](cassyx-migrate/README.md) | `core`, `bulk` | `MigrateFactory` | no | 75% |
| [`cassyx-license`](cassyx-license/README.md) | none | `LicenseFactory` | no | 90% |
| [`cassyx-api`](cassyx-api/README.md) | all of the above | Spring Boot app | yes | 60% |

Each library module exposes a public `io.cassyx.<module>.api` package (interfaces + immutable
records) and a private `io.cassyx.<module>.impl` package. **Siblings depend on `…api` only** —
including `cassyx-api`, which wires beans exclusively through the `…api` factories.

## Build

```bash
./mvnw verify                 # unit tests, ArchUnit, JaCoCo report
./mvnw verify -Dcassyx.it=true  # + Testcontainers integration tests (needs Docker)
./mvnw -P mutation verify -pl cassyx-core,cassyx-bulk   # PIT, 70% mutation gate
./mvnw spotless:check checkstyle:check                  # lint (CI `lint` job)
```

No local Java 21 or Maven? Build in a container:

```bash
docker run --rm -v "$PWD/..":/w -w /w/backend -v "$HOME/.m2":/root/.m2 \
  maven:3.9-eclipse-temurin-21 mvn -B verify
```

## Enforced invariants

* `ModularityArchitectureTest` (in `cassyx-api`, runs in `mvn test` and the CI `arch` job) fails the
  build on any `org.springframework` import below `cassyx-api` and on any cross-module `…impl`
  import. Both rules were verified to go red on a deliberate violation before being committed.
* Extension points are `ServiceLoader` SPIs with `META-INF/services` wiring, never `if/else`:
  `Encoder`, `Sink` (bulk) · `PaymentProvider` (license) · `ImportSource` (migrate) ·
  `CapabilityProbe` (core).
* Secrets use `io.cassyx.core.api.Secret`: `toString()` redacts and Jackson serialises it as
  `null`, so a credential cannot leak into a log line or a response DTO by accident (plan §2.3).

## Coverage gates

JaCoCo `check` is bound to `verify` with the per-module gates above. It is **skipped by default**
while the modules are skeletons (`cassyx.coverage.skip=true` in the parent POM). Phase 1 agents flip
that to `false` once their module has real logic — see the comment block in `pom.xml`.

## Testing

One **shared** Cassandra 5.x Testcontainer for the whole suite
(`io.cassyx.core.testsupport.CassandraSingleton`, published as the `cassyx-core` test-jar). Extend
`IntegrationTestBase`; never start your own container. Integration tests are named `*IT`, run under
Failsafe, and are enabled by `-Dcassyx.it=true`.
