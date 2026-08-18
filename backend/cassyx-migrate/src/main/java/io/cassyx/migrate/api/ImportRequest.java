package io.cassyx.migrate.api;

import java.util.Map;
import java.util.Objects;

/**
 * Immutable import job description.
 *
 * @param location file path, URI or JDBC URL, depending on the source
 * @param columnMapping source column to target CQL column; empty means identity mapping
 * @param dryRunRows when &gt; 0, only this many rows are previewed and nothing is written
 */
public record ImportRequest(
    String sourceId,
    String location,
    String targetKeyspace,
    String targetTable,
    Map<String, String> columnMapping,
    Map<String, String> options,
    int dryRunRows) {

  public ImportRequest {
    Objects.requireNonNull(sourceId, "sourceId");
    Objects.requireNonNull(location, "location");
    columnMapping = columnMapping == null ? Map.of() : Map.copyOf(columnMapping);
    options = options == null ? Map.of() : Map.copyOf(options);
  }

  public static ImportRequest of(String sourceId, String location) {
    return new ImportRequest(sourceId, location, null, null, Map.of(), Map.of(), 0);
  }

  public boolean isDryRun() {
    return dryRunRows > 0;
  }
}
