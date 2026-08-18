package io.cassyx.core.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.datastax.oss.driver.api.core.CqlIdentifier;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.ProtocolVersion;
import com.datastax.oss.driver.api.core.cql.ColumnDefinitions;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.metadata.EndPoint;
import com.datastax.oss.driver.api.core.metadata.Metadata;
import com.datastax.oss.driver.api.core.metadata.Node;
import io.cassyx.core.api.Capability;
import io.cassyx.core.api.CapabilityProbe;
import io.cassyx.core.api.CapabilitySupport;
import io.cassyx.core.api.ClusterFlavor;
import io.cassyx.core.api.ClusterProbeResult;
import io.cassyx.core.api.CoreFactory;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The connect-time probe of plan section 7.1, driven against a mocked driver so the detection rules
 * are covered without six real clusters.
 */
class DefaultCapabilityProbeTest {

  private final DefaultCapabilityProbe probe = new DefaultCapabilityProbe();

  @Test
  void isDiscoverableViaServiceLoader() {
    assertThat(CoreFactory.capabilityProbes()).extracting(CapabilityProbe::id).contains("cassyx-default");
  }

  @Test
  void detectsApacheCassandraFromSystemLocal() {
    CqlSession session =
        sessionReturning(
            Map.of(
                "release_version", "5.0.2",
                "cluster_name", "Test Cluster",
                "partitioner", "org.apache.cassandra.dht.Murmur3Partitioner",
                "data_center", "datacenter1"));

    ClusterProbeResult result = probe.probeCluster(session, null).orElseThrow();

    assertThat(result.flavor()).isEqualTo(ClusterFlavor.CASSANDRA);
    assertThat(result.releaseVersion()).isEqualTo("5.0.2");
    assertThat(result.clusterName()).isEqualTo("Test Cluster");
    assertThat(result.supports(Capability.SAI)).isTrue();
    assertThat(result.supports(Capability.VECTOR_ANN)).isTrue();
    assertThat(result.versionString()).isEqualTo("5.0.2");
  }

  @Test
  @DisplayName("dse_version in system.local is the DSE tell")
  void detectsDse() {
    CqlSession session =
        sessionReturning(
            Map.of("release_version", "4.0.0.6816", "dse_version", "6.8.35", "cluster_name", "dse"));

    ClusterProbeResult result = probe.probeCluster(session, null).orElseThrow();

    assertThat(result.flavor()).isEqualTo(ClusterFlavor.DSE);
    assertThat(result.dseVersion()).isEqualTo("6.8.35");
    assertThat(result.versionString()).isEqualTo("6.8.35");
    assertThat(result.supports(Capability.DSE_SEARCH)).isTrue();
  }

  @Test
  @DisplayName("Amazon Keyspaces is detected from its own partitioner and reports NO token scan")
  void detectsAmazonKeyspacesFromPartitioner() {
    CqlSession session =
        sessionReturning(
            Map.of(
                "release_version", "3.11.2",
                "partitioner", DefaultCapabilityProbe.KEYSPACES_PARTITIONER,
                "cluster_name", "Amazon Keyspaces"));

    ClusterProbeResult result = probe.probeCluster(session, null).orElseThrow();

    assertThat(result.flavor()).isEqualTo(ClusterFlavor.AMAZON_KEYSPACES);
    assertThat(result.supports(Capability.TOKEN_RANGE_SCAN)).isFalse();
    assertThat(result.bulkReadStrategy())
        .isEqualTo(io.cassyx.core.api.BulkReadStrategy.PLAIN_PAGING);
    assertThat(result.status(Capability.TOKEN_RANGE_SCAN).support())
        .isEqualTo(CapabilitySupport.UNSUPPORTED);
  }

  @Test
  @DisplayName("an amazonaws endpoint is enough even when the partitioner column is missing")
  void detectsAmazonKeyspacesFromEndpoint() {
    CqlSession session = sessionReturning(Map.of("release_version", "3.11.2"));
    stubNode(session, "cassandra.eu-west-1.amazonaws.com:9142", "eu-west-1");

    assertThat(probe.probeCluster(session, null).orElseThrow().flavor())
        .isEqualTo(ClusterFlavor.AMAZON_KEYSPACES);
  }

  @Test
  void detectsScyllaFromSystemVersions() {
    CqlSession session = sessionReturning(Map.of("release_version", "3.0.8"));
    when(session.execute(anyString()))
        .thenAnswer(
            invocation -> {
              String cql = invocation.getArgument(0);
              return cql.contains("system.versions")
                  ? mock(ResultSet.class)
                  : resultSetFor(Map.of("release_version", "3.0.8"));
            });

    assertThat(probe.probeCluster(session, null).orElseThrow().flavor())
        .isEqualTo(ClusterFlavor.SCYLLA);
  }

  @Test
  @DisplayName("Astra looks like Cassandra on purpose, so the connection-mode hint decides")
  void usesTheAstraHint() {
    CqlSession session = sessionReturning(Map.of("release_version", "4.0.0.6816"));

    ClusterProbeResult result = probe.probeCluster(session, ClusterFlavor.ASTRA).orElseThrow();

    assertThat(result.flavor()).isEqualTo(ClusterFlavor.ASTRA);
    assertThat(result.supports(Capability.UDF_UDA)).isFalse();
    assertThat(result.supports(Capability.SAI)).isTrue();
  }

  @Test
  void detectsAstraFromItsControlPlaneClusterName() {
    CqlSession session =
        sessionReturning(Map.of("release_version", "4.0.0.6816", "cluster_name", "cndb"));

    assertThat(probe.probeCluster(session, null).orElseThrow().flavor())
        .isEqualTo(ClusterFlavor.ASTRA);
  }

  @Test
  void detectsAstraFromItsAuthKeyspace() {
    CqlSession session = sessionReturning(Map.of("release_version", "4.0.0.6816"));
    Map<CqlIdentifier, Object> keyspaces = new LinkedHashMap<>();
    keyspaces.put(CqlIdentifier.fromInternal("data_endpoint_auth"), new Object());
    when(session.getMetadata().getKeyspaces()).thenAnswer(invocation -> keyspaces);

    assertThat(probe.probeCluster(session, null).orElseThrow().flavor())
        .isEqualTo(ClusterFlavor.ASTRA);
  }

  @Test
  @DisplayName("an on-the-wire signal beats a contradicting hint")
  void doesNotTrustAHintOverEvidence() {
    CqlSession session =
        sessionReturning(
            Map.of("release_version", "3.11.2", "partitioner", DefaultCapabilityProbe.KEYSPACES_PARTITIONER));

    assertThat(probe.probeCluster(session, ClusterFlavor.ASTRA).orElseThrow().flavor())
        .isEqualTo(ClusterFlavor.AMAZON_KEYSPACES);
  }

  @Test
  @DisplayName("a cluster that refuses system.local degrades to UNKNOWN instead of failing connect")
  void survivesAnUnreadableSystemLocal() {
    CqlSession session = mock(CqlSession.class, RETURNS_DEEP_STUBS);
    when(session.execute(anyString())).thenThrow(new IllegalStateException("Unauthorized"));
    when(session.getMetadata().getNodes()).thenReturn(Map.of());
    when(session.getContext().getProtocolVersion()).thenReturn(ProtocolVersion.V4);

    ClusterProbeResult result = probe.probeCluster(session, null).orElseThrow();

    assertThat(result.flavor()).isEqualTo(ClusterFlavor.CASSANDRA);
    assertThat(result.releaseVersion()).isNull();
  }

  @Test
  void returnsEmptyForANullSession() {
    assertThat(probe.probeCluster(null, null)).isEmpty();
  }

  @Test
  void reportsNodeCountAndDatacenters() {
    CqlSession session = sessionReturning(Map.of("release_version", "5.0.2"));
    stubNode(session, "10.0.0.1:9042", "dc1");

    ClusterProbeResult result = probe.probeCluster(session, null).orElseThrow();

    assertThat(result.nodeCount()).isEqualTo(1);
    assertThat(result.datacenters()).containsExactly("dc1");
    assertThat(result.protocolVersion()).isEqualTo("V5");
  }

  @Test
  void narrowsToClusterCapabilitiesForFeatureCode() {
    CqlSession session = sessionReturning(Map.of("release_version", "5.0.2"));

    Optional<io.cassyx.core.api.ClusterCapabilities> narrow = probe.probe(session);

    assertThat(narrow).isPresent();
    assertThat(narrow.orElseThrow().supports(Capability.SAI)).isTrue();
  }

  /* ------------------------------------------------------------------ fixtures */

  private static CqlSession sessionReturning(Map<String, String> systemLocal) {
    CqlSession session = mock(CqlSession.class, RETURNS_DEEP_STUBS);
    when(session.execute(anyString()))
        .thenAnswer(
            invocation -> {
              String cql = invocation.getArgument(0);
              if (cql.contains("system.versions")) {
                throw new IllegalStateException("unconfigured table versions");
              }
              return resultSetFor(systemLocal);
            });
    when(session.getMetadata().getNodes()).thenReturn(Map.of());
    when(session.getMetadata().getKeyspaces()).thenReturn(Map.of());
    when(session.getContext().getProtocolVersion()).thenReturn(ProtocolVersion.V5);
    return session;
  }

  private static ResultSet resultSetFor(Map<String, String> values) {
    ColumnDefinitions definitions = mock(ColumnDefinitions.class);
    Row row = mock(Row.class);
    when(row.getColumnDefinitions()).thenReturn(definitions);
    when(definitions.contains(anyString()))
        .thenAnswer(invocation -> values.containsKey(invocation.<String>getArgument(0)));
    when(row.getString(anyString()))
        .thenAnswer(invocation -> values.get(invocation.<String>getArgument(0)));
    ResultSet resultSet = mock(ResultSet.class);
    when(resultSet.one()).thenReturn(row);
    return resultSet;
  }

  private static void stubNode(CqlSession session, String endpoint, String datacenter) {
    Node node = mock(Node.class);
    when(node.getEndPoint()).thenReturn(new FixedEndPoint(endpoint));
    when(node.getDatacenter()).thenReturn(datacenter);
    Metadata metadata = session.getMetadata();
    when(metadata.getNodes()).thenReturn(Map.of(UUID.randomUUID(), node));
  }

  /** A real EndPoint: Mockito cannot stub {@code toString()}, which is what the probe reads. */
  private record FixedEndPoint(String label) implements EndPoint {

    @Override
    public java.net.SocketAddress resolve() {
      return java.net.InetSocketAddress.createUnresolved("127.0.0.1", 9042);
    }

    @Override
    public String asMetricPrefix() {
      return label;
    }

    @Override
    public String toString() {
      return label;
    }
  }
}
