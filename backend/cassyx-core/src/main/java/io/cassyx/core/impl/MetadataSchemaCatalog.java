package io.cassyx.core.impl;

import com.datastax.oss.driver.api.core.CqlIdentifier;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.metadata.schema.ColumnMetadata;
import com.datastax.oss.driver.api.core.metadata.schema.KeyspaceMetadata;
import io.cassyx.core.api.CassyxCoreException;
import io.cassyx.core.api.KeyspaceSummary;
import io.cassyx.core.api.SchemaCatalog;
import io.cassyx.core.api.TableSummary;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** Reads the driver's live, event-driven metadata cache. Never queries {@code system_schema}. */
public final class MetadataSchemaCatalog implements SchemaCatalog {

  private static final Set<String> SYSTEM_KEYSPACES =
      Set.of(
          "system",
          "system_auth",
          "system_distributed",
          "system_schema",
          "system_traces",
          "system_views",
          "system_virtual_schema",
          "dse_system",
          "dse_system_local",
          "dse_security",
          "dse_perf",
          "dse_insights",
          "solr_admin",
          "data_endpoint_auth");

  /** Visible for testing - the "Show system" filter of plan section 4. */
  public static boolean isSystemKeyspace(String name) {
    return name != null
        && (SYSTEM_KEYSPACES.contains(name.toLowerCase(java.util.Locale.ROOT))
            || name.toLowerCase(java.util.Locale.ROOT).startsWith("system_"));
  }

  @Override
  public List<KeyspaceSummary> keyspaces(CqlSession session, boolean includeSystem) {
    return session.getMetadata().getKeyspaces().values().stream()
        .map(
            ks ->
                new KeyspaceSummary(
                    ks.getName().asInternal(),
                    ks.isDurableWrites(),
                    ks.getReplication(),
                    isSystemKeyspace(ks.getName().asInternal())))
        .filter(ks -> includeSystem || !ks.system())
        .sorted(Comparator.comparing(KeyspaceSummary::name))
        .toList();
  }

  @Override
  public List<TableSummary> tables(CqlSession session, String keyspace) {
    KeyspaceMetadata ks =
        session
            .getMetadata()
            .getKeyspace(CqlIdentifier.fromInternal(keyspace))
            .orElseThrow(() -> new CassyxCoreException("Unknown keyspace '" + keyspace + "'"));
    return ks.getTables().values().stream()
        .map(
            t ->
                new TableSummary(
                    keyspace,
                    t.getName().asInternal(),
                    t.getPartitionKey().stream().map(c -> c.getName().asInternal()).toList(),
                    t.getClusteringColumns().keySet().stream()
                        .map(ColumnMetadata::getName)
                        .map(CqlIdentifier::asInternal)
                        .toList(),
                    t.getColumns().size()))
        .sorted(Comparator.comparing(TableSummary::name))
        .toList();
  }
}
