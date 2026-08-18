# cassyx-core

Session creation, schema catalog, paged query execution, CQL script splitting, cluster capability
detection and the Astra DevOps client. **Plain Java — no Spring, no web layer.** Its only runtime
dependencies are the Cassandra driver, SLF4J and Jackson (for the Astra DevOps JSON).

Entry point: `io.cassyx.core.api.CoreFactory`.

## Usage

```java
import com.datastax.oss.driver.api.core.CqlSession;
import io.cassyx.core.api.*;
import java.util.List;

ConnectionSpec spec = ConnectionSpec.cassandra("local", List.of("127.0.0.1:9042"), "datacenter1");

try (CqlSession session = CoreFactory.sessionFactory().open(spec)) {
  // schema tree
  CoreFactory.schemaCatalog().keyspaces(session, false).forEach(System.out::println);

  // one page of results, with a continuation token
  QueryResultPage page = CoreFactory.queryExecutor()
      .execute(session, QueryRequest.of("SELECT * FROM system.local"));
  System.out.println(page.rows());
  System.out.println("more pages? " + page.hasMorePages());

  // capability gating (plan §7.1)
  CoreFactory.detectCapabilities(session)
      .ifPresent(caps -> System.out.println(caps.supports(Capability.VECTOR_ANN)));
}

// multi-statement scripts, split by a real lexer (string literals and UDF bodies contain ';')
CoreFactory.statementSplitter().split("SELECT 1; INSERT INTO t(a) VALUES ('a;b');");
```

## Astra secure connect bundles (§3 / §3.1)

```java
AstraDevOpsClient astra = CoreFactory.astraDevOpsClient(Secret.of(System.getenv("ASTRA_TOKEN")));

astra.listDatabases().forEach(System.out::println);                  // no UUID typing
astra.downloadBundle("db-uuid", ScbSelector.defaultBundleIn("us-east1"),
                     Path.of("/tmp/scb.zip"));

// PATH mode: server-side path, confined to CASSYX_SCB_PATH_ROOT (default /etc/cassyx/scb)
Path bundle = CoreFactory.scbPathResolver().resolve("prod.zip");
```

`region` and `scbType` are **orthogonal**: `ScbType` has exactly two values, `DEFAULT` and
`CUSTOM`. The DataStax reference client documents a third (`region`) that its switch never
implements; we reject it explicitly instead of silently falling through, and we default a null type
to `DEFAULT` rather than NPE-ing. See `ScbType` and `SecureBundleSelection`.

**The Astra token is never logged**, including on DevOps API error paths — enforced by
`AstraDevOpsClientTokenLoggingTest`. Wrap every credential in `Secret`: it redacts in `toString()`
and serialises as `null`.

## SPI

`CapabilityProbe` — `ServiceLoader`-discovered, ordered by `priority()`. `DefaultCapabilityProbe`
handles Apache Cassandra; add a class plus a `META-INF/services` line for DSE, Astra, Keyspaces or
Scylla.

## Test support

`io.cassyx.core.testsupport.CassandraSingleton` / `IntegrationTestBase` are published as a test-jar
and give the whole product ONE shared Cassandra 5.x container (plan §11.2).
