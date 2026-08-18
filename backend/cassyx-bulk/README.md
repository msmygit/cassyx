# cassyx-bulk

Token-range parallel unload, bulk load, count/statistics, and the `Encoder` / `Sink` SPIs.
**Plain Java — no Spring.** Depends only on `cassyx-core`.

Entry point: `io.cassyx.bulk.api.BulkFactory`.

## Usage

```java
import io.cassyx.bulk.api.*;
import java.nio.file.*;
import java.util.*;

Encoder csv = BulkFactory.encoder("csv");
Sink sink = BulkFactory.sinkForTarget("/data/export");

try (var out = sink.open("/data/export", "part-0001." + csv.fileExtension(), Map.of());
     Encoder.Writer writer = csv.open(out, Encoder.EncoderContext.of(List.of("id", "name")))) {
  writer.write(Map.of("id", 1, "name", "ada"));
}

UnloadRequest request = UnloadRequest.of("demo", "users", "csv", "/data/export");
// UnloadEngine implementation lands with Phase 1 workstream D:
// UnloadResult result = engine.unload(session, request, ProgressListener.noop());
```

## SPIs

| SPI | Key | Shipped implementation | Planned |
| --- | --- | --- | --- |
| `Encoder` | `format()` | `CsvEncoder` | JSON/JSONL, Parquet, XML, Excel |
| `Sink` | `scheme()` | `FileSink` | `http` (streaming download), `s3` |

Both are `ServiceLoader`-discovered. Adding Parquet means adding one class and one
`META-INF/services` line — never editing an `if/else` chain.

## Two engines, deliberately (plan §5.2 / §5.3)

* **Native token-range engine** — oversplit to ~10k splits and feed a work-stealing queue:
  `splitEvenly` divides by *token count*, not data volume, so skewed partitions make equal ranges
  take wildly unequal time. Always `unwrap()` a range before querying; CQL cannot express the
  wrapping range and querying it silently returns wrong results.
* **DSBulk (1.11.1)** — run **out of process** via `ProcessBuilder` from the distribution shipped in
  the image. DSBulk's `application.conf` collides with Spring/Typesafe Config on a shared classpath,
  so `dsbulk-runner` is deliberately *not* a dependency of this module; it is pinned (with the
  CVE-2026-24400 / CVE-2023-6378 overrides) in the parent `<dependencyManagement>` only.
