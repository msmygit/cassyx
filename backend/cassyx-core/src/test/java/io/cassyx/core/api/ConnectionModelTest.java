package io.cassyx.core.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.cassyx.core.api.astra.ScbSelector;
import io.cassyx.core.api.astra.ScbType;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** The immutable value objects the connections layer is built out of (plan sections 2.3, 3, 7.1). */
class ConnectionModelTest {

  @Nested
  class Connections {

    @Test
    void cassandraModeCarriesContactPointsAndDatacenter() {
      ConnectionSpec spec =
          ConnectionSpec.cassandra("local", List.of("127.0.0.1:9042"), "datacenter1");

      assertThat(spec.contactPoints()).containsExactly("127.0.0.1:9042");
      assertThat(spec.localDatacenter()).isEqualTo("datacenter1");
      assertThat(spec.isAstra()).isFalse();
      assertThat(spec.isAdvanced()).isFalse();
      assertThat(spec.hasPassword()).isFalse();
    }

    @Test
    void astraModeCarriesTheAstraSettings() {
      ConnectionSpec spec =
          ConnectionSpec.astra(
              "prod-eu",
              new AstraConnection(
                  Secret.of("AstraCS:abc:def"),
                  "f9a1b3c4-1111-2222-3333-444455556666",
                  ScbAcquisitionMode.AUTO_DOWNLOAD,
                  ScbSelector.defaultBundleIn("us-east1"),
                  null,
                  null));

      assertThat(spec.isAstra()).isTrue();
      assertThat(spec.astraConnection()).isPresent();
      assertThat(spec.astra().selector().scbType()).isEqualTo(ScbType.DEFAULT);
    }

    @Test
    void advancedModeCarriesRawHocon() {
      ConnectionSpec spec =
          ConnectionSpec.advanced("exotic", "datastax-java-driver { basic.contact-points = [] }");

      assertThat(spec.isAdvanced()).isTrue();
      assertThat(spec.advancedConfig()).contains("datastax-java-driver");
    }

    @Test
    @DisplayName("toString never reveals a password, a token or a HOCON blob that may contain one")
    void redactsEverySecretInToString() {
      ConnectionSpec spec =
          ConnectionSpec.builder("prod")
              .contactPoints(List.of("10.0.0.1:9042"))
              .localDatacenter("dc1")
              .credentials("cassandra", Secret.of("hunter2-the-real-password"))
              .advancedConfig("advanced.auth-provider.password = leaky")
              .ssh(
                  new SshTunnelSpec(
                      "bastion", 22, "ec2-user", Secret.of("bastion-pw"), null, null, 0, null, 0, false, null))
              .build();

      String rendered = spec.toString();

      assertThat(rendered)
          .doesNotContain("hunter2-the-real-password")
          .doesNotContain("bastion-pw")
          .doesNotContain("leaky")
          .contains("<redacted>");
    }

    @Test
    void builderDefaultsBlanksToNullSoDownstreamChecksAreSimple() {
      ConnectionSpec spec =
          ConnectionSpec.builder("x")
              .contactPoints(List.of("h:9042"))
              .localDatacenter("  ")
              .credentials("  ", null)
              .protocolVersion("")
              .defaultKeyspace("   ")
              .build();

      assertThat(spec.localDatacenter()).isNull();
      assertThat(spec.username()).isNull();
      assertThat(spec.protocolVersion()).isNull();
      assertThat(spec.defaultKeyspace()).isNull();
      assertThat(spec.ssl()).isEqualTo(SslSpec.disabled());
    }

    @Test
    @DisplayName("withContactPoints is how the SSH tunnel redirects the driver at the local forward")
    void replacesContactPointsForATunnel() {
      ConnectionSpec original =
          ConnectionSpec.builder("prod")
              .contactPoints(List.of("10.0.1.20:9042"))
              .localDatacenter("dc1")
              .requestTimeout(Duration.ofSeconds(12))
              .build();

      ConnectionSpec tunnelled = original.withContactPoints(List.of("127.0.0.1:53001"));

      assertThat(tunnelled.contactPoints()).containsExactly("127.0.0.1:53001");
      assertThat(tunnelled.localDatacenter()).isEqualTo("dc1");
      assertThat(tunnelled.requestTimeout()).isEqualTo(Duration.ofSeconds(12));
    }

    @Test
    void requiresAName() {
      assertThatThrownBy(() -> ConnectionSpec.builder(null)).isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  class SshTunnels {

    @Test
    void appliesTheStandardPortDefaults() {
      SshTunnelSpec spec =
          new SshTunnelSpec("bastion", 0, "ec2-user", Secret.of("pw"), null, null, 0, null, 0, false, null);

      assertThat(spec.port()).isEqualTo(22);
      assertThat(spec.remotePort()).isEqualTo(9042);
      assertThat(spec.remoteHost()).isEqualTo("127.0.0.1");
      assertThat(spec.hasPassword()).isTrue();
      assertThat(spec.hasPrivateKey()).isFalse();
    }

    @Test
    void requiresHostUsernameAndSomeCredential() {
      assertThatThrownBy(
              () -> new SshTunnelSpec(null, 22, "u", Secret.of("p"), null, null, 0, null, 0, false, null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("host");
      assertThatThrownBy(
              () -> new SshTunnelSpec("h", 22, " ", Secret.of("p"), null, null, 0, null, 0, false, null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("username");
      assertThatThrownBy(() -> new SshTunnelSpec("h", 22, "u", null, null, null, 0, null, 0, false, null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("password or a private key");
    }

    @Test
    @DisplayName("strict host key checking without a pinned key is rejected, not silently downgraded")
    void strictCheckingRequiresAPinnedKey() {
      assertThatThrownBy(
              () -> new SshTunnelSpec("h", 22, "u", Secret.of("p"), null, null, 0, null, 0, true, null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("knownHostsEntry");
    }

    @Test
    void redactsCredentialsInToString() {
      SshTunnelSpec spec =
          new SshTunnelSpec(
              "bastion", 22, "ec2-user", Secret.of("super-secret-pw"), null, null, 0, null, 0, false, null);

      assertThat(spec.toString()).doesNotContain("super-secret-pw").contains("hasPassword=true");
    }

    @Test
    void comparesByValue() {
      SshTunnelSpec one =
          new SshTunnelSpec("h", 22, "u", Secret.of("p"), null, null, 0, "r", 9042, false, null);
      SshTunnelSpec two =
          new SshTunnelSpec("h", 22, "u", Secret.of("p"), null, null, 0, "r", 9042, false, null);

      assertThat(one).isEqualTo(two).hasSameHashCodeAs(two);
      assertThat(one).isNotEqualTo("not a spec");
    }
  }

  @Nested
  class Ssl {

    @Test
    void disabledByDefaultWithNoStores() {
      SslSpec ssl = SslSpec.disabled();

      assertThat(ssl.enabled()).isFalse();
      assertThat(ssl.hostnameValidation()).isTrue();
      assertThat(ssl.hasTruststore()).isFalse();
      assertThat(ssl.hasKeystore()).isFalse();
      assertThat(ssl.truststore()).isNull();
      assertThat(ssl.keystore()).isNull();
    }

    @Test
    @DisplayName("store bytes are defensively copied - a caller cannot mutate stored TLS material")
    void copiesStoreBytes() {
      byte[] store = {1, 2, 3};
      SslSpec ssl =
          new SslSpec(true, true, store, Secret.of("tpw"), store, Secret.of("kpw"), List.of("TLS_AES_256_GCM_SHA384"));

      store[0] = 99;

      assertThat(ssl.truststore()).startsWith((byte) 1);
      assertThat(ssl.keystore()).startsWith((byte) 1);
      assertThat(ssl.cipherSuites()).containsExactly("TLS_AES_256_GCM_SHA384");
    }

    @Test
    void neverRendersStorePasswords() {
      SslSpec ssl =
          new SslSpec(true, false, new byte[] {1}, Secret.of("truststore-pw"), null, null, null);

      assertThat(ssl.toString())
          .doesNotContain("truststore-pw")
          .contains("hasTruststore=true")
          .contains("hasKeystore=false");
    }
  }

  @Nested
  class Capabilities {

    @Test
    void wireNamesMatchTheApiContract() {
      assertThat(Capability.SAI.wireName()).isEqualTo("sai");
      assertThat(Capability.VECTOR_ANN.wireName()).isEqualTo("vector");
      assertThat(Capability.TOKEN_RANGE_SCAN.wireName()).isEqualTo("tokenRangeScan");
      assertThat(Capability.MATERIALIZED_VIEWS.wireName()).isEqualTo("materializedViews");
      assertThat(Capability.UDF_UDA.wireName()).isEqualTo("udfUda");
      assertThat(Capability.DESCRIBE_STATEMENT.wireName()).isEqualTo("describeStatement");
    }

    @Test
    void wireNamesRoundTrip() {
      for (Capability capability : Capability.values()) {
        assertThat(Capability.fromWireName(capability.wireName())).isEqualTo(capability);
        assertThat(Capability.fromWireName(capability.name())).isEqualTo(capability);
      }
    }

    @Test
    void rejectsAnUnknownWireName() {
      assertThatThrownBy(() -> Capability.fromWireName("teleportation"))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> Capability.fromWireName(null))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void supportUsability() {
      assertThat(CapabilitySupport.SUPPORTED.usable()).isTrue();
      assertThat(CapabilitySupport.PARTIAL.usable()).isTrue();
      assertThat(CapabilitySupport.UNSUPPORTED.usable()).isFalse();
      assertThat(CapabilitySupport.UNKNOWN.usable()).isFalse();
    }

    @Test
    void statusFactoriesNormaliseBlanks() {
      assertThat(CapabilityStatus.supported(Capability.SAI).reason()).isNull();
      assertThat(CapabilityStatus.supportedSince(Capability.SAI, "5.0").since()).isEqualTo("5.0");
      assertThat(CapabilityStatus.partial(Capability.SAI, " ").reason()).isNull();
      assertThat(CapabilityStatus.unsupported(Capability.SAI, "no").usable()).isFalse();
      assertThat(CapabilityStatus.unknown(Capability.SAI, "?").support())
          .isEqualTo(CapabilitySupport.UNKNOWN);
      assertThat(new CapabilityStatus(Capability.SAI, null, null, null).support())
          .isEqualTo(CapabilitySupport.UNKNOWN);
      assertThatThrownBy(() -> new CapabilityStatus(null, null, null, null))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    void probeResultDerivesTheBulkStrategyFromTheCapability() {
      ClusterProbeResult scannable =
          probeWith(Map.of(Capability.TOKEN_RANGE_SCAN, CapabilityStatus.supported(Capability.TOKEN_RANGE_SCAN)));
      ClusterProbeResult paged =
          probeWith(
              Map.of(
                  Capability.TOKEN_RANGE_SCAN,
                  CapabilityStatus.unsupported(Capability.TOKEN_RANGE_SCAN, "Keyspaces")));

      assertThat(scannable.bulkReadStrategy()).isEqualTo(BulkReadStrategy.TOKEN_RANGE_SCAN);
      assertThat(paged.bulkReadStrategy()).isEqualTo(BulkReadStrategy.PLAIN_PAGING);
    }

    @Test
    void anAbsentCapabilityIsUnknownRatherThanAnError() {
      ClusterProbeResult result = probeWith(Map.of());

      assertThat(result.status(Capability.SAI).support()).isEqualTo(CapabilitySupport.UNKNOWN);
      assertThat(result.supports(Capability.SAI)).isFalse();
      assertThatThrownBy(() -> result.status(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void narrowsToTheSetFeatureCodeGatesOn() {
      ClusterProbeResult result =
          probeWith(
              Map.of(
                  Capability.SAI, CapabilityStatus.supported(Capability.SAI),
                  Capability.ROLES_PERMISSIONS,
                      CapabilityStatus.partial(Capability.ROLES_PERMISSIONS, "Astra"),
                  Capability.DSE_SEARCH,
                      CapabilityStatus.unsupported(Capability.DSE_SEARCH, "not DSE")));

      ClusterCapabilities narrow = result.capabilities();

      assertThat(narrow.supports(Capability.SAI)).isTrue();
      assertThat(narrow.supports(Capability.ROLES_PERMISSIONS)).isTrue();
      assertThat(narrow.supports(Capability.DSE_SEARCH)).isFalse();
    }

    @Test
    void prefersTheDseVersionUsersRecognise() {
      ClusterProbeResult dse =
          new ClusterProbeResult(
              ClusterFlavor.DSE, "dse", "4.0.0.6816", "6.8.35", "V4", null, 1, List.of(), Map.of(), Instant.EPOCH);

      assertThat(dse.versionString()).isEqualTo("6.8.35");
      assertThat(dse.dseVersionOpt()).contains("6.8.35");
    }

    @Test
    void unknownIsSafeToConstructAndDeduplicatesDatacenters() {
      assertThat(ClusterProbeResult.unknown().flavor()).isEqualTo(ClusterFlavor.UNKNOWN);
      assertThat(ClusterProbeResult.unknown().versionString()).isEqualTo("unknown");
      assertThat(ClusterProbeResult.unknown().dseVersionOpt()).isEmpty();
      assertThat(
              new ClusterProbeResult(
                      null, null, null, null, null, null, 0, List.of("dc1", "dc1"), null, null)
                  .datacenters())
          .containsExactly("dc1");
    }
  }

  @Nested
  class Encryption {

    @Test
    void encryptedValueCopiesItsBytesAndNeverPrintsThem() {
      byte[] nonce = {1, 2, 3};
      byte[] ciphertext = {9, 8, 7};
      EncryptedValue value = new EncryptedValue(nonce, ciphertext);

      nonce[0] = 42;
      ciphertext[0] = 42;

      assertThat(value.nonce()).startsWith((byte) 1);
      assertThat(value.ciphertext()).startsWith((byte) 9);
      assertThat(value.length()).isEqualTo(3);
      assertThat(value.toString()).isEqualTo("EncryptedValue[3 bytes]").doesNotContain("9");
    }

    @Test
    void encryptedValuesCompareByContent() {
      EncryptedValue one = new EncryptedValue(new byte[] {1}, new byte[] {2});
      EncryptedValue two = new EncryptedValue(new byte[] {1}, new byte[] {2});

      assertThat(one).isEqualTo(two).hasSameHashCodeAs(two);
      assertThat(one).isNotEqualTo(new EncryptedValue(new byte[] {1}, new byte[] {3}));
      assertThat(one).isNotEqualTo("no");
      assertThatThrownBy(() -> new EncryptedValue(null, new byte[] {1}))
          .isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  class NotConnected {

    @Test
    void namesTheConnectionAndTellsTheUserWhatToDo() {
      ConnectionNotOpenException e = new ConnectionNotOpenException("abc");

      assertThat(e.connectionId()).isEqualTo("abc");
      assertThat(e).isInstanceOf(CassyxCoreException.class).hasMessageContaining("Connect it first");
    }
  }

  private static ClusterProbeResult probeWith(Map<Capability, CapabilityStatus> statuses) {
    return new ClusterProbeResult(
        ClusterFlavor.CASSANDRA,
        "Test Cluster",
        "5.0.2",
        null,
        "V5",
        "org.apache.cassandra.dht.Murmur3Partitioner",
        3,
        List.of("datacenter1"),
        statuses,
        Instant.parse("2026-08-17T10:25:42Z"));
  }
}
