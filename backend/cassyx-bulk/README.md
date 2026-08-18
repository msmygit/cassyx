# cassyx-bulk

Token-range parallel unload, bulk load, count/statistics, and the `Encoder` / `Sink` SPIs.
**Plain Java — no Spring.** Depends only on `cassyx-core`.

Entry point: `io.cassyx.bulk.api.BulkFactory`.

## Usage

**A `CqlSession` is the only thing this module needs** — no Spring, no web layer, no session
registry (plan §2.1). Drop the jar into any Java project and run a token-range parallel unload:

```java
import io.cassyx.bulk.api.*;
import com.datastax.oss.driver.api.core.CqlSession;

try (CqlSession session = CqlSession.builder().build()) {
  UnloadResult result =
      BulkFactory.unloadEngine()
          .unload(
              session,
              UnloadRequest.of("demo", "users", "parquet", "/data/export"),
              progress -> System.printf("%.1f%%%n", progress.fraction() * 100));

  System.out.println(result.rowsWritten() + " rows at " + result.rowsPerSecond() + "/s");
}
```

Streaming straight into an HTTP response (never buffering — a 50M-row unload holds flat memory):

```java
BulkFactory.unloadEngine()
    .unloadTo(session, request, servletOutputStream, listener, Cancellation.of(cancelledFlag));
```

Row counts and the top-N largest partitions, in one token-ranged pass:

```java
CountEngine.CountResult stats = BulkFactory.countEngine().count(session, "demo", "sensor_readings");
```

Or use the SPIs on their own:

```java
Encoder csv = BulkFactory.encoder("csv");
Sink sink = BulkFactory.sinkForTarget("/data/export");

try (var out = sink.open("/data/export", "part-0001." + csv.fileExtension(), Map.of());
     Encoder.Writer writer = csv.open(out, Encoder.EncoderContext.of(List.of("id", "name")))) {
  writer.write(Map.of("id", 1, "name", "ada"));
}
```

## SPIs

| SPI | Key | Shipped implementations |
| --- | --- | --- |
| `Encoder` | `format()` | `csv`, `json`, `jsonl`, `parquet`, `xml`, `xlsx` |
| `Sink` | `scheme()` | `file` (mounted volume), `http`/`https` (chunked streaming upload), `s3` (multipart) |

Every encoder is forward-only except **Parquet**, which cannot be: the format's footer holds the
row-group offsets and is written last, so it stages to a temp file with a bounded row-group size and
copies out on close. XLSX uses POI's `SXSSF` (sliding window + temp spill), never `XSSF`.

Both are `ServiceLoader`-discovered. Adding Parquet means adding one class and one
`META-INF/services` line — never editing an `if/else` chain.

## Two engines, deliberately (plan §5.2 / §5.3)

* **Native token-range engine** (`TokenRangeUnloadEngine`) — oversplit to ~10k splits and feed a
  single work-stealing queue drained by N virtual threads:
  `splitEvenly` divides by *token count*, not data volume, so skewed partitions make equal ranges
  take wildly unequal time. Always `unwrap()` a range before querying; CQL cannot express the
  wrapping range and querying it silently returns wrong results. Bounds are start-exclusive and
  end-inclusive — making both inclusive duplicates every boundary partition, making both exclusive
  drops it, and neither raises an error.
  On a cluster with no token ring (**Amazon Keyspaces**, plan §7.1) the engine degrades to a single
  paged full scan and reports it as a warning, rather than failing.

  The engine asserts its own completeness in production, not just in tests: it refuses to return a
  result whose split count or row count does not reconcile. The property test
  (`TokenRangeUnloadIT` — union of splits = `count(*)`, no gaps, no duplicates, against a table with
  a 20 000-row hot partition) is the highest-value test in the repository.
* **DSBulk (1.11.1)** — run **out of process** via `ProcessBuilder` from the distribution shipped in
  the image. DSBulk's `application.conf` collides with Spring/Typesafe Config on a shared classpath,
  so `dsbulk-runner` is deliberately *not* a dependency of this module; it is pinned (with the
  CVE-2026-24400 / CVE-2023-6378 overrides) in the parent `<dependencyManagement>` only.
