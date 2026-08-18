package io.cassyx.bulk.impl.dsbulk;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.metadata.Metadata;
import com.datastax.oss.driver.api.core.metadata.Node;
import com.datastax.oss.driver.api.core.metadata.schema.ColumnMetadata;
import com.datastax.oss.driver.api.core.metadata.schema.KeyspaceMetadata;
import com.datastax.oss.driver.api.core.metadata.schema.TableMetadata;
import io.cassyx.bulk.api.dsbulk.DsbulkProbe;
import io.cassyx.core.api.ClusterCapabilities;
import io.cassyx.core.api.ClusterFlavor;
import io.cassyx.core.api.CoreFactory;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Builds the {@link DsbulkProbe} the derivation table is computed from (plan section 5.3: "at job
 * creation, probe the cluster and derive").
 *
 * <p>Everything here comes from the driver's live, event-driven metadata cache - never from a
 * {@code system_schema} query. The driver already maintains it and keeps it current through schema
 * change events, so polling would be both slower and staler.
 *
 * <p>Every lookup degrades to a sane fallback. A probe failure must reduce the quality of the
 * derived defaults, never prevent a job from being planned at all.
 */
public final class DsbulkClusterProber {

  private DsbulkClusterProber() {}

  /**
   * @param keyspace may be null for a query-driven unload; the table-shaped facts then stay false
   */
  public static DsbulkProbe probe(CqlSession session, String keyspace, String table) {
    if (session == null) {
      return DsbulkProbe.UNKNOWN;
    }
    Metadata metadata = session.getMetadata();
    int nodes = Math.max(1, metadata.getNodes().size());
    int cores = Runtime.getRuntime().availableProcessors();
    ClusterFlavor flavour = flavourOf(session);

    boolean hasClustering = false;
    boolean counters = false;
    Map<String, String> columnTypes = new LinkedHashMap<>();

    Optional<TableMetadata> target = table(metadata, keyspace, table);
    if (target.isPresent()) {
      TableMetadata t = target.get();
      hasClustering = !t.getClusteringColumns().isEmpty();
      for (ColumnMetadata column : t.getColumns().values()) {
        String type = column.getType().asCql(true, false);
        columnTypes.put(column.getName().asInternal(), type);
        if ("counter".equalsIgnoreCase(type)) {
          counters = true;
        }
      }
    }

    return new DsbulkProbe(
        nodes,
        cores,
        flavour,
        hasClustering,
        counters,
        // Astra is the case the plan calls out: DSBulk 1.9+ detects the server's own rate limiter
        // and honours it, so cassyx must not layer a client-side throttle on top of it.
        flavour == ClusterFlavor.ASTRA,
        null,
        columnTypes);
  }

  /** Nodes reported as up in the local datacenter, which is the fan-out the derivations use. */
  public static int localNodeCount(CqlSession session, String localDatacenter) {
    if (session == null) {
      return 1;
    }
    int count = 0;
    for (Node node : session.getMetadata().getNodes().values()) {
      if (localDatacenter == null || localDatacenter.equals(node.getDatacenter())) {
        count++;
      }
    }
    return Math.max(1, count);
  }

  static Optional<TableMetadata> table(Metadata metadata, String keyspace, String table) {
    if (keyspace == null || keyspace.isBlank() || table == null || table.isBlank()) {
      return Optional.empty();
    }
    return metadata.getKeyspace(keyspace).flatMap(ks -> tableOf(ks, table));
  }

  private static Optional<TableMetadata> tableOf(KeyspaceMetadata keyspace, String table) {
    return keyspace.getTable(table);
  }

  /** Delegates to cassyx-core's ServiceLoader-discovered capability probes (plan section 7.1). */
  static ClusterFlavor flavourOf(CqlSession session) {
    try {
      return CoreFactory.detectCapabilities(session)
          .map(ClusterCapabilities::flavor)
          .orElse(ClusterFlavor.UNKNOWN);
    } catch (RuntimeException e) {
      return ClusterFlavor.UNKNOWN;
    }
  }
}
