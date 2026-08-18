package io.cassyx.core.impl;

import io.cassyx.core.api.Capability;
import io.cassyx.core.api.CapabilityStatus;
import io.cassyx.core.api.ClusterFlavor;
import java.util.EnumMap;
import java.util.Map;

/**
 * The plan section 7.1 capability matrix, as a pure function.
 *
 * <p>Deliberately takes strings rather than a {@code CqlSession}: the matrix is the part that is
 * easy to get wrong and expensive to test against six real clusters, so it is separated from the
 * sniffing that produces its inputs and is exhaustively unit-tested with no network at all.
 *
 * <p>Every non-supported entry carries a reason, because that reason is rendered verbatim as the
 * tooltip on the hidden feature. "SAI unavailable" is a dead end; "SAI requires Cassandra 5.x,
 * DSE 6.8+ or Astra - this cluster reports 4.1.3" tells the user what to do.
 */
public final class CapabilityMatrix {

  private CapabilityMatrix() {}

  /**
   * @param flavor the detected product
   * @param releaseVersion {@code system.local.release_version}
   * @param dseVersion {@code system.local.dse_version}, or null
   */
  public static Map<Capability, CapabilityStatus> forCluster(
      ClusterFlavor flavor, String releaseVersion, String dseVersion) {
    Map<Capability, CapabilityStatus> matrix = new EnumMap<>(Capability.class);
    ClusterFlavor detected = flavor == null ? ClusterFlavor.UNKNOWN : flavor;
    switch (detected) {
      case CASSANDRA -> cassandra(matrix, releaseVersion);
      case DSE -> dse(matrix, dseVersion);
      case ASTRA -> astra(matrix);
      case AMAZON_KEYSPACES -> keyspaces(matrix);
      case SCYLLA -> scylla(matrix, releaseVersion);
      case UNKNOWN -> unknown(matrix);
      default -> unknown(matrix);
    }
    return matrix;
  }

  private static void cassandra(Map<Capability, CapabilityStatus> m, String version) {
    int major = Versions.major(version);
    String reported = describe(version);

    if (major >= 5) {
      m.put(Capability.SAI, CapabilityStatus.supportedSince(Capability.SAI, "5.0"));
      m.put(Capability.VECTOR_ANN, CapabilityStatus.supportedSince(Capability.VECTOR_ANN, "5.0"));
      m.put(
          Capability.MATERIALIZED_VIEWS,
          CapabilityStatus.supported(Capability.MATERIALIZED_VIEWS));
    } else {
      m.put(
          Capability.SAI,
          CapabilityStatus.unsupported(
              Capability.SAI,
              "Storage-Attached Indexes require Cassandra 5.x, DSE 6.8+ or Astra. This cluster "
                  + "reports " + reported + "."));
      m.put(
          Capability.VECTOR_ANN,
          CapabilityStatus.unsupported(
              Capability.VECTOR_ANN,
              "vector<float, N> columns and ANN search require Cassandra 5.x or Astra. This "
                  + "cluster reports " + reported + "."));
      m.put(
          Capability.MATERIALIZED_VIEWS,
          CapabilityStatus.partial(
              Capability.MATERIALIZED_VIEWS,
              "Materialized views are marked experimental before Cassandra 5.0 and are disabled by "
                  + "default in many deployments (enable_materialized_views)."));
    }

    m.put(Capability.UDF_UDA, CapabilityStatus.supported(Capability.UDF_UDA));
    m.put(Capability.TRUNCATE, CapabilityStatus.supported(Capability.TRUNCATE));
    m.put(Capability.TOKEN_RANGE_SCAN, CapabilityStatus.supported(Capability.TOKEN_RANGE_SCAN));
    m.put(Capability.ROLES_PERMISSIONS, CapabilityStatus.supported(Capability.ROLES_PERMISSIONS));
    m.put(Capability.TRACING, CapabilityStatus.supported(Capability.TRACING));
    m.put(Capability.LWT, CapabilityStatus.supported(Capability.LWT));
    m.put(Capability.COUNTERS, CapabilityStatus.supported(Capability.COUNTERS));
    m.put(Capability.DESCRIBE_STATEMENT, CapabilityStatus.supported(Capability.DESCRIBE_STATEMENT));
    m.put(
        Capability.VIRTUAL_TABLES,
        major >= 4
            ? CapabilityStatus.supportedSince(Capability.VIRTUAL_TABLES, "4.0")
            : CapabilityStatus.unsupported(
                Capability.VIRTUAL_TABLES,
                "Virtual tables (system_views) arrived in Cassandra 4.0. This cluster reports "
                    + reported + "."));
    m.put(
        Capability.DSE_SEARCH,
        CapabilityStatus.unsupported(
            Capability.DSE_SEARCH, "DSE Search is only available on DataStax Enterprise."));
  }

  private static void dse(Map<Capability, CapabilityStatus> m, String dseVersion) {
    boolean sai = Versions.atLeast(dseVersion, 6, 8);
    m.put(
        Capability.SAI,
        sai
            ? CapabilityStatus.supportedSince(Capability.SAI, "6.8")
            : CapabilityStatus.unsupported(
                Capability.SAI,
                "Storage-Attached Indexes arrived in DSE 6.8. This cluster reports DSE "
                    + describe(dseVersion) + "."));
    m.put(
        Capability.VECTOR_ANN,
        CapabilityStatus.unsupported(
            Capability.VECTOR_ANN,
            "DSE has no vector<float, N> type; vector search needs Cassandra 5.x or Astra."));
    m.put(Capability.MATERIALIZED_VIEWS, CapabilityStatus.supported(Capability.MATERIALIZED_VIEWS));
    m.put(Capability.UDF_UDA, CapabilityStatus.supported(Capability.UDF_UDA));
    m.put(Capability.TRUNCATE, CapabilityStatus.supported(Capability.TRUNCATE));
    m.put(Capability.TOKEN_RANGE_SCAN, CapabilityStatus.supported(Capability.TOKEN_RANGE_SCAN));
    m.put(Capability.DSE_SEARCH, CapabilityStatus.supported(Capability.DSE_SEARCH));
    m.put(Capability.ROLES_PERMISSIONS, CapabilityStatus.supported(Capability.ROLES_PERMISSIONS));
    m.put(Capability.TRACING, CapabilityStatus.supported(Capability.TRACING));
    m.put(Capability.LWT, CapabilityStatus.supported(Capability.LWT));
    m.put(Capability.COUNTERS, CapabilityStatus.supported(Capability.COUNTERS));
    m.put(Capability.DESCRIBE_STATEMENT, CapabilityStatus.supported(Capability.DESCRIBE_STATEMENT));
    m.put(
        Capability.VIRTUAL_TABLES,
        CapabilityStatus.unsupported(
            Capability.VIRTUAL_TABLES,
            "DSE 6.x is built on the Cassandra 3.11 storage engine, which predates virtual "
                + "tables."));
  }

  private static void astra(Map<Capability, CapabilityStatus> m) {
    m.put(Capability.SAI, CapabilityStatus.supported(Capability.SAI));
    m.put(Capability.VECTOR_ANN, CapabilityStatus.supported(Capability.VECTOR_ANN));
    m.put(Capability.TRUNCATE, CapabilityStatus.supported(Capability.TRUNCATE));
    m.put(Capability.TOKEN_RANGE_SCAN, CapabilityStatus.supported(Capability.TOKEN_RANGE_SCAN));
    m.put(Capability.LWT, CapabilityStatus.supported(Capability.LWT));
    m.put(Capability.COUNTERS, CapabilityStatus.supported(Capability.COUNTERS));
    m.put(Capability.DESCRIBE_STATEMENT, CapabilityStatus.supported(Capability.DESCRIBE_STATEMENT));
    m.put(
        Capability.MATERIALIZED_VIEWS,
        CapabilityStatus.unsupported(
            Capability.MATERIALIZED_VIEWS,
            "Astra DB does not allow materialized views. Model the second access pattern as its "
                + "own table, or use an SAI index."));
    m.put(
        Capability.UDF_UDA,
        CapabilityStatus.unsupported(
            Capability.UDF_UDA,
            "Astra DB does not permit user-defined functions or aggregates (they execute arbitrary "
                + "code on the server)."));
    m.put(
        Capability.DSE_SEARCH,
        CapabilityStatus.unsupported(
            Capability.DSE_SEARCH, "DSE Search is only available on DataStax Enterprise."));
    m.put(
        Capability.ROLES_PERMISSIONS,
        CapabilityStatus.partial(
            Capability.ROLES_PERMISSIONS,
            "Astra manages access through application tokens and roles in the Astra console; CQL "
                + "role management is restricted."));
    m.put(
        Capability.TRACING,
        CapabilityStatus.unsupported(
            Capability.TRACING,
            "Astra DB does not expose the system_traces keyspace, so query traces cannot be read "
                + "back."));
    m.put(
        Capability.VIRTUAL_TABLES,
        CapabilityStatus.unsupported(
            Capability.VIRTUAL_TABLES, "Astra DB does not expose virtual tables."));
  }

  private static void keyspaces(Map<Capability, CapabilityStatus> m) {
    m.put(
        Capability.TOKEN_RANGE_SCAN,
        CapabilityStatus.unsupported(
            Capability.TOKEN_RANGE_SCAN,
            "Amazon Keyspaces does not implement token() range scans, so bulk reads fall back to "
                + "plain driver paging. Throughput is bounded by your provisioned read capacity."));
    m.put(
        Capability.TRUNCATE,
        CapabilityStatus.unsupported(
            Capability.TRUNCATE,
            "Amazon Keyspaces does not support TRUNCATE. Drop and recreate the table instead."));
    m.put(
        Capability.SAI,
        CapabilityStatus.unsupported(
            Capability.SAI, "Amazon Keyspaces has no secondary or storage-attached indexes."));
    m.put(
        Capability.VECTOR_ANN,
        CapabilityStatus.unsupported(
            Capability.VECTOR_ANN, "Amazon Keyspaces has no vector type or ANN search."));
    m.put(
        Capability.MATERIALIZED_VIEWS,
        CapabilityStatus.unsupported(
            Capability.MATERIALIZED_VIEWS, "Amazon Keyspaces does not support materialized views."));
    m.put(
        Capability.UDF_UDA,
        CapabilityStatus.unsupported(
            Capability.UDF_UDA,
            "Amazon Keyspaces does not support user-defined functions or aggregates."));
    m.put(
        Capability.DSE_SEARCH,
        CapabilityStatus.unsupported(
            Capability.DSE_SEARCH, "DSE Search is only available on DataStax Enterprise."));
    m.put(
        Capability.ROLES_PERMISSIONS,
        CapabilityStatus.partial(
            Capability.ROLES_PERMISSIONS,
            "Amazon Keyspaces authorises through AWS IAM, not CQL roles. Manage access in IAM."));
    m.put(
        Capability.TRACING,
        CapabilityStatus.unsupported(
            Capability.TRACING, "Amazon Keyspaces does not expose system_traces."));
    m.put(
        Capability.COUNTERS,
        CapabilityStatus.unsupported(
            Capability.COUNTERS, "Amazon Keyspaces does not support counter columns."));
    m.put(
        Capability.VIRTUAL_TABLES,
        CapabilityStatus.unsupported(
            Capability.VIRTUAL_TABLES, "Amazon Keyspaces does not expose virtual tables."));
    m.put(Capability.LWT, CapabilityStatus.supported(Capability.LWT));
    m.put(Capability.DESCRIBE_STATEMENT, CapabilityStatus.supported(Capability.DESCRIBE_STATEMENT));
  }

  private static void scylla(Map<Capability, CapabilityStatus> m, String version) {
    m.put(
        Capability.SAI,
        CapabilityStatus.unsupported(
            Capability.SAI,
            "ScyllaDB implements Cassandra secondary indexes, not Storage-Attached Indexes."));
    m.put(
        Capability.VECTOR_ANN,
        CapabilityStatus.unsupported(
            Capability.VECTOR_ANN, "ScyllaDB has no vector<float, N> type or ANN search."));
    m.put(Capability.MATERIALIZED_VIEWS, CapabilityStatus.supported(Capability.MATERIALIZED_VIEWS));
    m.put(Capability.TRUNCATE, CapabilityStatus.supported(Capability.TRUNCATE));
    m.put(Capability.TOKEN_RANGE_SCAN, CapabilityStatus.supported(Capability.TOKEN_RANGE_SCAN));
    m.put(Capability.ROLES_PERMISSIONS, CapabilityStatus.supported(Capability.ROLES_PERMISSIONS));
    m.put(Capability.TRACING, CapabilityStatus.supported(Capability.TRACING));
    m.put(Capability.LWT, CapabilityStatus.supported(Capability.LWT));
    m.put(Capability.COUNTERS, CapabilityStatus.supported(Capability.COUNTERS));
    m.put(Capability.DESCRIBE_STATEMENT, CapabilityStatus.supported(Capability.DESCRIBE_STATEMENT));
    m.put(
        Capability.UDF_UDA,
        CapabilityStatus.partial(
            Capability.UDF_UDA,
            "ScyllaDB implements UDFs in Lua and only when experimental features are enabled; "
                + "Java and JavaScript function bodies will be rejected."));
    m.put(
        Capability.DSE_SEARCH,
        CapabilityStatus.unsupported(
            Capability.DSE_SEARCH, "DSE Search is only available on DataStax Enterprise."));
    m.put(
        Capability.VIRTUAL_TABLES,
        Versions.atLeast(version, 5, 0)
            ? CapabilityStatus.partial(
                Capability.VIRTUAL_TABLES,
                "ScyllaDB exposes a subset of the Cassandra virtual tables.")
            : CapabilityStatus.unsupported(
                Capability.VIRTUAL_TABLES, "This ScyllaDB release exposes no virtual tables."));
  }

  private static void unknown(Map<Capability, CapabilityStatus> m) {
    for (Capability capability : Capability.values()) {
      m.put(
          capability,
          CapabilityStatus.unknown(
              capability,
              "cassyx could not identify this cluster, so features are offered without a "
                  + "guarantee. Statements that the server rejects will surface its own error."));
    }
    // The one thing worth assuming even when the product is unknown: anything speaking the CQL
    // binary protocol supports token() unless it is Keyspaces, and guessing PLAIN_PAGING would
    // silently make every bulk export an order of magnitude slower.
    m.put(
        Capability.TOKEN_RANGE_SCAN,
        CapabilityStatus.partial(
            Capability.TOKEN_RANGE_SCAN,
            "Assumed available: this cluster was not recognised, but token() range scans are part "
                + "of core CQL. If exports fail, switch the bulk engine to plain paging."));
  }

  private static String describe(String version) {
    return version == null || version.isBlank() ? "an unknown version" : version;
  }
}
