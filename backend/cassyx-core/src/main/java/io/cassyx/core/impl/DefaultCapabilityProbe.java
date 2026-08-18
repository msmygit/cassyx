package io.cassyx.core.impl;

import com.datastax.oss.driver.api.core.CqlIdentifier;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.metadata.Metadata;
import com.datastax.oss.driver.api.core.metadata.Node;
import com.datastax.oss.driver.api.core.metadata.TokenMap;
import io.cassyx.core.api.Capability;
import io.cassyx.core.api.CapabilityProbe;
import io.cassyx.core.api.CapabilityStatus;
import io.cassyx.core.api.ClusterFlavor;
import io.cassyx.core.api.ClusterProbeResult;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The connect-time capability probe of plan section 7.1: read {@code system.local}, work out which
 * of six products we are actually talking to, then apply {@link CapabilityMatrix}.
 *
 * <p>Detection order matters and is deliberate - the signals are checked strongest first:
 *
 * <ol>
 *   <li><b>Amazon Keyspaces</b> - its partitioner is literally
 *       {@code com.amazonaws.cassandra.DefaultPartitioner}, and its endpoints are
 *       {@code cassandra.<region>.amazonaws.com}. Getting this one right is the highest-stakes
 *       detection in the file: Keyspaces has no {@code token()} range scan, so a misdetection makes
 *       every bulk export fail rather than fall back to paging.
 *   <li><b>DSE</b> - {@code system.local} carries a {@code dse_version} column that no other
 *       product has.
 *   <li><b>ScyllaDB</b> - only Scylla has {@code system.versions}.
 *   <li><b>Astra</b> - looks like stock Cassandra on purpose. Its serverless control plane reports
 *       {@code cluster_name = cndb} and it exposes the {@code data_endpoint_auth} keyspace, but the
 *       reliable signal is that we connected with a secure connect bundle, which is what
 *       {@code hint} carries.
 *   <li>otherwise Apache Cassandra, gated on {@code release_version}.
 * </ol>
 *
 * <p>The probe never throws. A cluster that refuses {@code SELECT * FROM system.local} (some locked
 * down deployments do) yields an {@link ClusterFlavor#UNKNOWN} result whose every capability is
 * {@code UNKNOWN} - the UI then offers features without a guarantee instead of showing an empty
 * application.
 */
public final class DefaultCapabilityProbe implements CapabilityProbe {

  private static final Logger LOG = LoggerFactory.getLogger(DefaultCapabilityProbe.class);

  /** Amazon Keyspaces' partitioner class - unique to it. */
  static final String KEYSPACES_PARTITIONER = "com.amazonaws.cassandra.DefaultPartitioner";

  private static final String SYSTEM_LOCAL = "SELECT * FROM system.local";
  private static final String SCYLLA_PROBE = "SELECT version FROM system.versions LIMIT 1";

  private final Clock clock;

  public DefaultCapabilityProbe() {
    this(Clock.systemUTC());
  }

  public DefaultCapabilityProbe(Clock clock) {
    this.clock = clock;
  }

  @Override
  public String id() {
    return "cassyx-default";
  }

  @Override
  public int priority() {
    return 1000;
  }

  @Override
  public Optional<ClusterProbeResult> probeCluster(CqlSession session, ClusterFlavor hint) {
    if (session == null) {
      return Optional.empty();
    }
    Row local = readSystemLocal(session);

    String releaseVersion = text(local, "release_version");
    String dseVersion = text(local, "dse_version");
    String clusterName = text(local, "cluster_name");
    String partitioner = partitioner(session, local);

    ClusterFlavor flavor =
        detectFlavor(session, hint, partitioner, dseVersion, clusterName);

    Map<Capability, CapabilityStatus> matrix =
        CapabilityMatrix.forCluster(flavor, releaseVersion, dseVersion);

    List<String> datacenters = new ArrayList<>();
    int nodeCount = 0;
    try {
      Map<java.util.UUID, Node> nodes = session.getMetadata().getNodes();
      nodeCount = nodes.size();
      TreeSet<String> sorted = new TreeSet<>();
      for (Node node : nodes.values()) {
        if (node.getDatacenter() != null) {
          sorted.add(node.getDatacenter());
        }
      }
      datacenters.addAll(sorted);
    } catch (RuntimeException e) {
      LOG.debug("Could not read node metadata while probing capabilities", e);
    }
    if (datacenters.isEmpty()) {
      String localDc = text(local, "data_center");
      if (localDc != null) {
        datacenters.add(localDc);
      }
    }

    String protocolVersion = protocolVersion(session);

    LOG.info(
        "Cluster '{}' detected as {} (release {}{}), {} node(s) across {}",
        clusterName == null ? "unknown" : clusterName,
        flavor,
        releaseVersion,
        dseVersion == null ? "" : ", DSE " + dseVersion,
        nodeCount,
        datacenters);

    return Optional.of(
        new ClusterProbeResult(
            flavor,
            clusterName,
            releaseVersion,
            dseVersion,
            protocolVersion,
            partitioner,
            nodeCount,
            datacenters,
            matrix,
            clock.instant()));
  }

  /** Visible for testing: the detection rules, isolated from the querying. */
  static ClusterFlavor detectFlavor(
      CqlSession session,
      ClusterFlavor hint,
      String partitioner,
      String dseVersion,
      String clusterName) {

    if (partitioner != null && partitioner.contains("amazonaws")) {
      return ClusterFlavor.AMAZON_KEYSPACES;
    }
    if (endpointsLookLikeKeyspaces(session)) {
      return ClusterFlavor.AMAZON_KEYSPACES;
    }
    if (dseVersion != null && !dseVersion.isBlank()) {
      return ClusterFlavor.DSE;
    }
    if (isScylla(session)) {
      return ClusterFlavor.SCYLLA;
    }
    if (hint == ClusterFlavor.ASTRA || looksLikeAstra(session, clusterName)) {
      return ClusterFlavor.ASTRA;
    }
    if (hint != null && hint != ClusterFlavor.UNKNOWN) {
      return hint;
    }
    return ClusterFlavor.CASSANDRA;
  }

  private static boolean endpointsLookLikeKeyspaces(CqlSession session) {
    try {
      for (Node node : session.getMetadata().getNodes().values()) {
        String endpoint = String.valueOf(node.getEndPoint()).toLowerCase(Locale.ROOT);
        if (endpoint.contains("amazonaws.com")) {
          return true;
        }
      }
    } catch (RuntimeException e) {
      return false;
    }
    return false;
  }

  private static boolean isScylla(CqlSession session) {
    try {
      // system.versions exists on ScyllaDB and nowhere else. A failed query here is the normal,
      // expected outcome on Cassandra, so it is not logged at anything above debug.
      session.execute(SCYLLA_PROBE);
      return true;
    } catch (RuntimeException e) {
      return false;
    }
  }

  private static boolean looksLikeAstra(CqlSession session, String clusterName) {
    if (clusterName != null && clusterName.equalsIgnoreCase("cndb")) {
      return true;
    }
    try {
      return session
          .getMetadata()
          .getKeyspaces()
          .containsKey(CqlIdentifier.fromInternal("data_endpoint_auth"));
    } catch (RuntimeException e) {
      return false;
    }
  }

  private static Row readSystemLocal(CqlSession session) {
    try {
      return session.execute(SYSTEM_LOCAL).one();
    } catch (RuntimeException e) {
      LOG.warn(
          "Could not read system.local while probing cluster capabilities ({}); "
              + "features will be offered without a compatibility guarantee",
          e.getClass().getSimpleName());
      return null;
    }
  }

  private static String partitioner(CqlSession session, Row local) {
    String fromRow = text(local, "partitioner");
    if (fromRow != null) {
      return fromRow;
    }
    try {
      Metadata metadata = session.getMetadata();
      return metadata.getTokenMap().map(TokenMap::getPartitionerName).orElse(null);
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static String protocolVersion(CqlSession session) {
    try {
      return session.getContext().getProtocolVersion().name();
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** Null-safe, column-may-not-exist-safe string read. */
  static String text(Row row, String column) {
    if (row == null) {
      return null;
    }
    try {
      if (!row.getColumnDefinitions().contains(column)) {
        return null;
      }
      String value = row.getString(column);
      return value == null || value.isBlank() ? null : value;
    } catch (RuntimeException e) {
      return null;
    }
  }
}
