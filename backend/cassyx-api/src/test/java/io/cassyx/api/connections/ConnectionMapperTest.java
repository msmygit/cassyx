package io.cassyx.api.connections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.cassyx.api.connections.dto.AstraSettings;
import io.cassyx.api.connections.dto.ConnectionMode;
import io.cassyx.api.connections.dto.ConnectionRequest;
import io.cassyx.api.connections.dto.ConnectionResponse;
import io.cassyx.api.connections.dto.ContactPoint;
import io.cassyx.api.connections.dto.ScbMode;
import io.cassyx.api.connections.dto.ScbType;
import io.cassyx.api.connections.dto.SshTunnelConfig;
import io.cassyx.api.connections.dto.SslConfig;
import io.cassyx.core.api.ClusterFlavor;
import io.cassyx.core.api.ConnectionSpec;
import io.cassyx.core.api.CoreFactory;
import io.cassyx.core.api.SecretCipher;
import io.cassyx.core.api.astra.ScbSelector;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The request/row/spec mapping - and, above all, that no secret ever crosses into a response.
 */
class ConnectionMapperTest {

  private static final String PASSWORD = "hunter2-the-real-password";
  private static final String ASTRA_TOKEN = "AstraCS:abcdef:0123456789";

  private final SecretCipher cipher = CoreFactory.secretCipher(CoreFactory.generateSecretKey());
  private final ConnectionMapper mapper = new ConnectionMapper(cipher);

  /* ------------------------------------------------------------------ secrecy */

  @Nested
  class Secrecy {

    @Test
    @DisplayName("no secret appears anywhere in the serialised response")
    void responseCarriesPresenceFlagsOnly() throws Exception {
      ConnectionRow row = mapper.apply(fullAstraRequest(), newRow());
      row.createdAt(Instant.EPOCH).updatedAt(Instant.EPOCH);

      ConnectionResponse response = mapper.toResponse(row, true);
      String json = objectMapper().writeValueAsString(response);

      assertThat(json)
          .doesNotContain(ASTRA_TOKEN)
          .doesNotContain(PASSWORD)
          .doesNotContain("bastion-password")
          .doesNotContain("truststore-password")
          .doesNotContain("BEGIN OPENSSH PRIVATE KEY");
      assertThat(response.hasPassword()).isTrue();
      assertThat(response.astra().hasAstraToken()).isTrue();
      assertThat(response.ssh().hasPassword()).isTrue();
      assertThat(response.ssh().hasPrivateKey()).isTrue();
      assertThat(response.ssl().hasTruststorePassword()).isTrue();
    }

    @Test
    @DisplayName("credentials are ciphertext in the row, never plaintext")
    void storesCiphertext() {
      ConnectionRow row = mapper.apply(fullAstraRequest(), newRow());

      assertThat(new String(row.password().ciphertext(), java.nio.charset.StandardCharsets.UTF_8))
          .doesNotContain(PASSWORD);
      assertThat(cipher.decryptSecret(row.password()).reveal()).isEqualTo(PASSWORD);
      assertThat(cipher.decryptSecret(row.astraToken()).reveal()).isEqualTo(ASTRA_TOKEN);
    }

    @Test
    void theRequestItselfNeverRendersItsSecrets() {
      assertThat(fullAstraRequest().toString())
          .doesNotContain(ASTRA_TOKEN)
          .doesNotContain(PASSWORD)
          .contains("<secrets redacted>");
    }

    @Test
    @DisplayName("null preserves a stored secret; empty string clears it")
    void secretUpdateSemantics() {
      ConnectionRow row = mapper.apply(cassandraRequest(PASSWORD), newRow());
      var stored = row.password();

      mapper.apply(cassandraRequest(null), row);
      assertThat(row.password()).isEqualTo(stored);

      mapper.apply(cassandraRequest(""), row);
      assertThat(row.password()).isNull();

      mapper.apply(cassandraRequest("a-new-password"), row);
      assertThat(cipher.decryptSecret(row.password()).reveal()).isEqualTo("a-new-password");
    }
  }

  /* ------------------------------------------------------------------ round trips */

  @Nested
  class RoundTrips {

    @Test
    void cassandraModeRoundTripsThroughTheRow() {
      ConnectionRow row = mapper.apply(cassandraRequest(PASSWORD), newRow());

      ConnectionResponse response = mapper.toResponse(row, false);

      assertThat(response.mode()).isEqualTo(ConnectionMode.CASSANDRA);
      assertThat(response.contactPoints())
          .extracting(ContactPoint::toHostPort)
          .containsExactly("10.0.0.1:9042", "10.0.0.2:9142");
      assertThat(response.localDatacenter()).isEqualTo("datacenter1");
      assertThat(response.tags()).containsExactly("dev", "local");
      assertThat(response.astra()).isNull();
      assertThat(response.connected()).isFalse();
    }

    @Test
    void buildsTheCoreSpecTheDriverNeeds() {
      ConnectionRow row = mapper.apply(cassandraRequest(PASSWORD), newRow());

      ConnectionSpec spec = mapper.toSpec(row);

      assertThat(spec.contactPoints()).containsExactly("10.0.0.1:9042", "10.0.0.2:9142");
      assertThat(spec.localDatacenter()).isEqualTo("datacenter1");
      assertThat(spec.username()).isEqualTo("cassandra");
      assertThat(spec.password().reveal()).isEqualTo(PASSWORD);
      assertThat(spec.requestTimeout()).isEqualTo(Duration.ofMillis(12000));
      assertThat(spec.protocolVersion()).isEqualTo("V5");
      assertThat(spec.defaultKeyspace()).isEqualTo("demo");
      assertThat(spec.isAstra()).isFalse();
    }

    @Test
    void astraSpecCarriesTheTokenAndTheOrthogonalSelector() {
      ConnectionRow row = mapper.apply(fullAstraRequest(), newRow());
      row.scbBundle(cipher.encrypt(new byte[] {1, 2, 3}));

      ConnectionSpec spec = mapper.toSpec(row);

      assertThat(spec.isAstra()).isTrue();
      assertThat(spec.astra().token().reveal()).isEqualTo(ASTRA_TOKEN);
      ScbSelector selector = spec.astra().selector();
      assertThat(selector.region()).isEqualTo("us-east1");
      assertThat(selector.scbType()).isEqualTo(io.cassyx.core.api.astra.ScbType.DEFAULT);
    }

    @Test
    void sshSpecCarriesTheTunnelAndThePinnedHostKey() {
      ConnectionRow row = mapper.apply(fullAstraRequest(), newRow());

      ConnectionSpec spec = mapper.toSpec(row);

      assertThat(spec.sshTunnel()).isPresent();
      assertThat(spec.sshTunnel().orElseThrow().host()).isEqualTo("bastion.example.com");
      assertThat(spec.sshTunnel().orElseThrow().strictHostKeyChecking()).isTrue();
      assertThat(spec.sshTunnel().orElseThrow().knownHostsEntry()).contains("ssh-ed25519");
    }

    @Test
    @DisplayName("Astra is hinted to the probe because it looks like Cassandra over CQL")
    void suppliesTheProbeHint() {
      assertThat(mapper.flavorHint(mapper.apply(fullAstraRequest(), newRow())))
          .isEqualTo(ClusterFlavor.ASTRA);
      assertThat(mapper.flavorHint(mapper.apply(cassandraRequest(null), newRow()))).isNull();
    }

    @Test
    void contactPointsRoundTripThroughTheStoredString() {
      assertThat(ConnectionMapper.parseContactPoints("a:1, b , c:3"))
          .extracting(ContactPoint::toHostPort)
          .containsExactly("a:1", "b:9042", "c:3");
      assertThat(ConnectionMapper.parseContactPoints(null)).isEmpty();
      assertThat(ConnectionMapper.parseContactPoints("  ")).isEmpty();
      assertThat(ConnectionMapper.joinContactPoints(List.of())).isNull();
      assertThat(ConnectionMapper.joinContactPoints(null)).isNull();
    }

    @Test
    void listsRoundTripThroughTheStoredString() {
      assertThat(ConnectionMapper.splitList("a, b,c")).containsExactly("a", "b", "c");
      assertThat(ConnectionMapper.splitList(null)).isEmpty();
      assertThat(ConnectionMapper.joinList(List.of())).isNull();
      assertThat(ConnectionMapper.joinList(List.of("a", "b"))).isEqualTo("a,b");
    }

    @Test
    void unknownStoredEnumsFallBackRatherThanThrow() {
      assertThat(ConnectionMapper.scbMode("nonsense")).isEqualTo(ScbMode.AUTO_DOWNLOAD);
      assertThat(ConnectionMapper.scbMode(null)).isEqualTo(ScbMode.AUTO_DOWNLOAD);
      assertThat(ConnectionMapper.scbType("custom")).isEqualTo(ScbType.CUSTOM);
      assertThat(ConnectionMapper.scbType("CUSTOM")).isEqualTo(ScbType.CUSTOM);
      assertThat(ConnectionMapper.scbType(null)).isEqualTo(ScbType.DEFAULT);
      assertThat(mapper.toResponse(newRow().mode("NONSENSE"), false).mode())
          .isEqualTo(ConnectionMode.CASSANDRA);
    }
  }

  /* ------------------------------------------------------------------ validation */

  @Nested
  class Validation {

    @Test
    void directModesNeedContactPointsAndALocalDatacenter() {
      assertThatThrownBy(
              () ->
                  mapper.validate(
                      request(ConnectionMode.CASSANDRA, List.of(), "dc1", null, null)))
          .isInstanceOf(ConnectionValidationException.class)
          .hasMessageContaining("contact point");

      assertThatThrownBy(
              () ->
                  mapper.validate(
                      request(
                          ConnectionMode.DSE, List.of(new ContactPoint("h", 9042)), null, null, null)))
          .isInstanceOf(ConnectionValidationException.class)
          .hasMessageContaining("local datacenter");
    }

    @Test
    void advancedModeNeedsHocon() {
      assertThatThrownBy(
              () -> mapper.validate(request(ConnectionMode.ADVANCED, List.of(), null, null, null)))
          .isInstanceOf(ConnectionValidationException.class)
          .hasMessageContaining("HOCON");
    }

    @Test
    void astraNeedsSettings() {
      assertThatThrownBy(
              () -> mapper.validate(request(ConnectionMode.ASTRA, List.of(), null, null, null)))
          .isInstanceOf(ConnectionValidationException.class)
          .hasMessageContaining("Astra settings");
    }

    @Test
    void autoDownloadNeedsADatabaseId() {
      assertThatThrownBy(
              () ->
                  mapper.validate(
                      astraRequest(
                          new AstraSettings(
                              ASTRA_TOKEN, ScbMode.AUTO_DOWNLOAD, null, null, null, null, null))))
          .isInstanceOf(ConnectionValidationException.class)
          .hasMessageContaining("databaseId");
    }

    @Test
    void pathModeNeedsAServerSidePath() {
      assertThatThrownBy(
              () ->
                  mapper.validate(
                      astraRequest(
                          new AstraSettings(ASTRA_TOKEN, ScbMode.PATH, null, null, null, null, ""))))
          .isInstanceOf(ConnectionValidationException.class)
          .hasMessageContaining("scbPath");
    }

    @Test
    @DisplayName("UPLOAD needs nothing up front - the bundle arrives on its own endpoint")
    void uploadModeValidatesWithoutABundle() {
      mapper.validate(
          astraRequest(new AstraSettings(ASTRA_TOKEN, ScbMode.UPLOAD, null, null, null, null, null)));
    }

    @Test
    @DisplayName("domain is required for custom and forbidden for default - the two orthogonal axes")
    void domainTracksScbType() {
      assertThatThrownBy(
              () ->
                  mapper.validate(
                      astraRequest(
                          new AstraSettings(
                              ASTRA_TOKEN, ScbMode.UPLOAD, null, null, ScbType.CUSTOM, null, null))))
          .isInstanceOf(ConnectionValidationException.class)
          .hasMessageContaining("domain is required");

      assertThatThrownBy(
              () ->
                  mapper.validate(
                      astraRequest(
                          new AstraSettings(
                              ASTRA_TOKEN,
                              ScbMode.UPLOAD,
                              null,
                              null,
                              ScbType.DEFAULT,
                              "cassandra.example.com",
                              null))))
          .isInstanceOf(ConnectionValidationException.class)
          .hasMessageContaining("must not be set");
    }

    @Test
    void rejectsATokenThatIsNotAnAstraToken() {
      assertThatThrownBy(
              () ->
                  mapper.validate(
                      astraRequest(
                          new AstraSettings(
                              "not-a-token", ScbMode.UPLOAD, null, null, null, null, null))))
          .isInstanceOf(ConnectionValidationException.class)
          .hasMessageContaining("AstraCS:");
    }

    @Test
    void refusesToBuildAnAstraSpecWithNoStoredToken() {
      ConnectionRow row =
          newRow()
              .mode(ConnectionMode.ASTRA.name())
              .scbAcquisitionMode(ScbMode.UPLOAD.name());

      assertThatThrownBy(() -> mapper.toSpec(row))
          .isInstanceOf(ConnectionValidationException.class)
          .hasMessageContaining("no stored token");
    }

    @Test
    void refusesToBuildAnUploadSpecWithNoStoredBundle() {
      ConnectionRow row = mapper.apply(uploadAstraRequest(), newRow());

      assertThatThrownBy(() -> mapper.toSpec(row))
          .isInstanceOf(ConnectionValidationException.class)
          .hasMessageContaining("No secure connect bundle has been uploaded");
    }

    @Test
    void surfacesTunnelMisconfigurationAsAFieldError() {
      ConnectionRow row =
          newRow()
              .mode(ConnectionMode.CASSANDRA.name())
              .contactPoints("h:9042")
              .localDatacenter("dc1")
              .sshEnabled(true)
              .sshHost("bastion")
              .sshUsername("ec2-user");

      assertThatThrownBy(() -> mapper.toSpec(row))
          .isInstanceOf(ConnectionValidationException.class)
          .hasMessageContaining("password or a private key");
    }
  }

  /* ------------------------------------------------------------------ fixtures */

  private static ConnectionRow newRow() {
    return new ConnectionRow().id("8f2b1c6e-2a55-4f47-9f2a-4c1c3f0d9a11").name("fixture");
  }

  /** Matches Spring Boot's auto-configured mapper, which knows about java.time. */
  private static ObjectMapper objectMapper() {
    return com.fasterxml.jackson.databind.json.JsonMapper.builder()
        .addModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
        .build();
  }

  private static ConnectionRequest cassandraRequest(String password) {
    return new ConnectionRequest(
        "local-dev",
        ConnectionMode.CASSANDRA,
        "Docker Compose Cassandra 5.0",
        List.of(new ContactPoint("10.0.0.1", 9042), new ContactPoint("10.0.0.2", 9142)),
        "datacenter1",
        "cassandra",
        password,
        "V5",
        "demo",
        12000,
        null,
        null,
        null,
        null,
        List.of("dev", "local"));
  }

  private static ConnectionRequest request(
      ConnectionMode mode,
      List<ContactPoint> contactPoints,
      String localDatacenter,
      String advancedConfig,
      AstraSettings astra) {
    return new ConnectionRequest(
        "x", mode, null, contactPoints, localDatacenter, null, null, null, null, null, astra,
        advancedConfig, null, null, null);
  }

  private static ConnectionRequest astraRequest(AstraSettings astra) {
    return request(ConnectionMode.ASTRA, List.of(), null, null, astra);
  }

  private static ConnectionRequest uploadAstraRequest() {
    return astraRequest(
        new AstraSettings(ASTRA_TOKEN, ScbMode.UPLOAD, null, null, null, null, null));
  }

  private static ConnectionRequest fullAstraRequest() {
    return new ConnectionRequest(
        "prod-eu",
        ConnectionMode.ASTRA,
        "Astra production",
        List.of(),
        null,
        null,
        PASSWORD,
        null,
        "demo",
        null,
        new AstraSettings(
            ASTRA_TOKEN,
            ScbMode.AUTO_DOWNLOAD,
            "f9a1b3c4-1111-2222-3333-444455556666",
            "us-east1",
            ScbType.DEFAULT,
            null,
            null),
        null,
        new SshTunnelConfig(
            true,
            "bastion.example.com",
            22,
            "ec2-user",
            "bastion-password",
            "-----BEGIN OPENSSH PRIVATE KEY-----\nnot-a-real-key\n-----END OPENSSH PRIVATE KEY-----",
            null,
            0,
            "10.0.1.20",
            9042,
            true,
            "bastion.example.com ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIPLACEHOLDER"),
        new SslConfig(true, true, "truststore-password", null, List.of("TLS_AES_256_GCM_SHA384")),
        List.of("prod"));
  }
}
