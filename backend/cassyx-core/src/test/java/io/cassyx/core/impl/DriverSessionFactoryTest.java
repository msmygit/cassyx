package io.cassyx.core.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.cassyx.core.api.AstraConnection;
import io.cassyx.core.api.CassyxCoreException;
import io.cassyx.core.api.ConnectionSpec;
import io.cassyx.core.api.ScbAcquisitionMode;
import io.cassyx.core.api.Secret;
import io.cassyx.core.api.SslSpec;
import java.io.ByteArrayOutputStream;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Session building for the three connection modes.
 *
 * <p>These tests stop short of {@code builder.build()} where that would need a live cluster - the
 * assertions are about the settings and the failure messages, which is where the defects live. The
 * connect path itself is covered by the Testcontainers suite.
 */
class DriverSessionFactoryTest {

  private final DriverSessionFactory factory = new DriverSessionFactory();

  @Test
  void parsesContactPointsWithAndWithoutAPort() {
    assertThat(DriverSessionFactory.parseContactPoint("10.0.0.1:9142").getPort()).isEqualTo(9142);
    assertThat(DriverSessionFactory.parseContactPoint("10.0.0.1").getPort()).isEqualTo(9042);
    assertThat(DriverSessionFactory.parseContactPoint("  host.example.com:9042  ").getHostString())
        .isEqualTo("host.example.com");
  }

  @Test
  void rejectsMalformedContactPoints() {
    assertThatThrownBy(() -> DriverSessionFactory.parseContactPoint(null))
        .isInstanceOf(CassyxCoreException.class);
    assertThatThrownBy(() -> DriverSessionFactory.parseContactPoint("  "))
        .isInstanceOf(CassyxCoreException.class);
    assertThatThrownBy(() -> DriverSessionFactory.parseContactPoint("host:not-a-port"))
        .isInstanceOf(CassyxCoreException.class)
        .hasMessageContaining("Invalid contact point");
  }

  @Test
  void refusesAConnectionWithNoContactPoints() {
    ConnectionSpec spec = ConnectionSpec.cassandra("empty", List.of(), "dc1");

    assertThatThrownBy(() -> factory.open(spec))
        .isInstanceOf(CassyxCoreException.class)
        .hasMessageContaining("no contact points");
  }

  @Test
  @DisplayName("a missing local datacenter is named explicitly - the driver's own error is opaque")
  void refusesAConnectionWithNoLocalDatacenter() {
    ConnectionSpec spec =
        ConnectionSpec.builder("no-dc").contactPoints(List.of("127.0.0.1:9042")).build();

    assertThatThrownBy(() -> factory.open(spec))
        .isInstanceOf(CassyxCoreException.class)
        .hasMessageContaining("nodetool status");
  }

  @Test
  @DisplayName("Astra without a resolvable bundle points at the three acquisition modes")
  void refusesAstraWithoutABundle() {
    ConnectionSpec spec =
        ConnectionSpec.astra(
            "prod-eu",
            new AstraConnection(
                Secret.of("AstraCS:a:b"),
                "f9a1b3c4-1111-2222-3333-444455556666",
                ScbAcquisitionMode.AUTO_DOWNLOAD,
                null,
                null,
                null));

    assertThatThrownBy(() -> factory.open(spec))
        .isInstanceOf(CassyxCoreException.class)
        .hasMessageContaining("No secure connect bundle")
        .hasMessageContaining("CASSYX_SCB_PATH_ROOT");
  }

  @Test
  void rejectsAdvancedConfigThatIsNotHocon() {
    ConnectionSpec spec = ConnectionSpec.advanced("exotic", "this is { not valid hocon");

    assertThatThrownBy(() -> factory.open(spec))
        .isInstanceOf(CassyxCoreException.class)
        .hasMessageContaining("not valid HOCON");
  }

  @Test
  @DisplayName("an unreachable cluster surfaces the connection name, never the credentials")
  void wrapsDriverFailuresWithoutLeakingCredentials() {
    ConnectionSpec spec =
        ConnectionSpec.builder("prod")
            // Port 1 is reserved and refuses immediately, so this fails fast rather than hanging.
            .contactPoints(List.of("127.0.0.1:1"))
            .localDatacenter("datacenter1")
            .credentials("cassandra", Secret.of("hunter2-the-real-password"))
            .requestTimeout(Duration.ofMillis(500))
            .build();

    assertThatThrownBy(() -> factory.open(spec))
        .isInstanceOf(CassyxCoreException.class)
        .hasMessageContaining("connection 'prod'")
        .hasMessageNotContaining("hunter2-the-real-password");
  }

  @Test
  void buildsAnSslEngineFactoryFromAnUploadedStore() throws Exception {
    byte[] store = selfSignedPkcs12("changeit");
    SslSpec ssl =
        new SslSpec(true, true, store, Secret.of("changeit"), store, Secret.of("changeit"), List.of());

    assertThat(DriverSessionFactory.sslEngineFactory(ssl)).isNotNull();
  }

  @Test
  void acceptsAnExplicitCipherSuiteList() throws Exception {
    byte[] store = selfSignedPkcs12("changeit");
    SslSpec ssl =
        new SslSpec(
            true, false, store, Secret.of("changeit"), null, null, List.of("TLS_AES_256_GCM_SHA384"));

    assertThat(DriverSessionFactory.sslEngineFactory(ssl)).isNotNull();
  }

  @Test
  @DisplayName("a wrong store password says so rather than failing later with a TLS handshake error")
  void reportsAnUnreadableStore() {
    SslSpec ssl =
        new SslSpec(true, true, new byte[] {1, 2, 3}, Secret.of("nope"), null, null, List.of());

    assertThatThrownBy(() -> DriverSessionFactory.sslEngineFactory(ssl))
        .isInstanceOf(CassyxCoreException.class)
        .hasMessageContaining("store password");
  }

  /** A throwaway PKCS12 store, so the SSL path is exercised without a checked-in binary fixture. */
  private static byte[] selfSignedPkcs12(String password) throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    generator.generateKeyPair();
    KeyStore store = KeyStore.getInstance("PKCS12");
    store.load(null, password.toCharArray());
    // An empty-but-valid store is enough: TrustManagerFactory and KeyManagerFactory both accept it,
    // and generating a real X.509 certificate would need a signing library we do not otherwise ship.
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    store.store(out, password.toCharArray());
    return out.toByteArray();
  }
}
