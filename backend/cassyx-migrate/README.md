# cassyx-migrate

CQL dump/restore, cluster-to-cluster keyspace copy, file and RDBMS import, and the `ImportSource`
SPI. **Plain Java — no Spring.** Depends on `cassyx-core` and `cassyx-bulk`.

Entry point: `io.cassyx.migrate.api.MigrateFactory`.

## Usage

```java
import io.cassyx.migrate.api.*;
import java.util.Map;

ImportSource csv = MigrateFactory.importSource("csv");
ImportRequest request = new ImportRequest(
    "csv", "/data/people.csv", "demo", "people", Map.of(), Map.of(), /* dryRunRows */ 20);

System.out.println(csv.columns(request));        // drives the column-mapping UI

try (ImportSource.ImportCursor cursor = csv.open(request)) {
  while (cursor.hasNext()) {
    System.out.println(cursor.next());           // dry-run preview: first 20 rows, nothing written
  }
}
```

## SPI

`ImportSource` — `ServiceLoader`-discovered by `id()`. `CsvImportSource` ships today; Excel, MySQL
and SQL Server are added as classes plus `META-INF/services` lines, with no changes here.

`CqlDumper`, `KeyspaceCopier` and `JdbcImporter` are the remaining interfaces; keyspace copy streams
cluster→cluster and never buffers to disk, and RF is remapped because source DC names rarely match
the target's.
