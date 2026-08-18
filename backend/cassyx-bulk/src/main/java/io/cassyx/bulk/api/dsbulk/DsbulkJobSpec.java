package io.cassyx.bulk.api.dsbulk;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * What the user asked for, before any defaults are derived.
 *
 * <p>{@code overrides} is a FLAT map of full DSBulk setting paths to values. The API layer owns the
 * nested {@code DsbulkSettings} JSON of the contract and flattens it here, which keeps cassyx-bulk
 * free of any knowledge of the wire format and means a new DSBulk option never needs a change in
 * this module (plan section 5.3: every group carries an {@code extra} passthrough map).
 *
 * @param format connector name: {@code csv} or {@code json}
 * @param url the connector URL - the unload sink or the load source
 * @param mapping DSBulk field-to-column mapping, e.g. {@code user_id=user_id, mail=email}
 * @param statsModes {@code count} workflow modes (plan section 5.4)
 * @param topPartitions N for the largest-partitions report
 */
public record DsbulkJobSpec(
    DsbulkOperation operation,
    String keyspace,
    String table,
    String query,
    String format,
    String url,
    String mapping,
    boolean dryRun,
    List<String> statsModes,
    int topPartitions,
    Map<String, String> overrides) {

  /** DSBulk's own default for {@code stats.modes}. */
  public static final List<String> DEFAULT_STATS_MODES = List.of("global");

  public DsbulkJobSpec {
    Objects.requireNonNull(operation, "operation");
    format = format == null || format.isBlank() ? "csv" : format.toLowerCase(Locale.ROOT);
    statsModes = statsModes == null || statsModes.isEmpty() ? DEFAULT_STATS_MODES : List.copyOf(statsModes);
    topPartitions = topPartitions <= 0 ? 10 : topPartitions;
    overrides = overrides == null ? Map.of() : Map.copyOf(overrides);
    if ((keyspace == null || keyspace.isBlank()) && (query == null || query.isBlank())) {
      throw new DsbulkException("A DSBulk job needs either keyspace+table or a query");
    }
    if (operation != DsbulkOperation.UNLOAD && (query == null || query.isBlank())
        && (table == null || table.isBlank())) {
      throw new DsbulkException("A " + operation + " job needs a table");
    }
  }

  /** Convenience constructor for an unload or count of a whole table. */
  public static DsbulkJobSpec table(
      DsbulkOperation operation, String keyspace, String table, String format, String url) {
    return new DsbulkJobSpec(
        operation, keyspace, table, null, format, url, null, false, null, 10, Map.of());
  }

  public boolean isQueryDriven() {
    return query != null && !query.isBlank();
  }

  /** Fully-qualified target name for log and job labels; never used to build CQL. */
  public String qualifiedName() {
    if (isQueryDriven()) {
      return "(query)";
    }
    return keyspace + "." + table;
  }
}
