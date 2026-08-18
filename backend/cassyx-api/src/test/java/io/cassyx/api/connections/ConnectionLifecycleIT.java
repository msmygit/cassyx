package io.cassyx.api.connections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.cassyx.api.connections.dto.ConnectionHealth;
import io.cassyx.api.connections.dto.ConnectionMode;
import io.cassyx.api.connections.dto.ConnectionRequest;
import io.cassyx.api.connections.dto.ConnectionResponse;
import io.cassyx.api.connections.dto.ConnectionTestResult;
import io.cassyx.api.connections.dto.ContactPoint;
import io.cassyx.api.connections.dto.SessionState;
import io.cassyx.core.api.Capability;
import io.cassyx.core.api.ConnectionNotOpenException;
import io.cassyx.core.api.CoreFactory;
import io.cassyx.core.api.ManagedSessionRegistry;
import io.cassyx.core.api.SecretCipher;
import io.cassyx.core.api.SessionFactory;
import io.cassyx.core.testsupport.CassandraSingleton;
import io.cassyx.core.testsupport.IntegrationTestBase;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

/**
 * The whole connection lifecycle - save, connect, probe, health, disconnect, delete - against the
 * shared Cassandra 5.x container and a real H2 database migrated by Flyway (plan section 11.2).
 *
 * <p>Wired by hand rather than through {@code @SpringBootTest}: the point is to exercise the
 * repository, mapper, crypto, registry and probe together, and a full application context would
 * drag in every other workstream's beans mid-flight.
 */
class ConnectionLifecycleIT extends IntegrationTestBase {

  private ManagedSessionRegistry registry;
  private ConnectionService connections;
  private ConnectionSessionService sessions;
  private org.springframework.jdbc.datasource.embedded.EmbeddedDatabase database;

  @BeforeEach
  void setUp() {
    database =
        new EmbeddedDatabaseBuilder()
            .setType(EmbeddedDatabaseType.H2)
            .setName("cassyx-it-" + UUID.randomUUID())
            .build();
    migrate(database);

    SecretCipher cipher = CoreFactory.secretCipher(CoreFactory.generateSecretKey());
    ConnectionMapper mapper = new ConnectionMapper(cipher);
    ConnectionRepository repository = new ConnectionRepository(new JdbcTemplate(database));
    SecureBundleHolder holder = new SecureBundleHolder();
    SessionFactory sessionFactory = CoreFactory.sessionFactory(holder.resolver());
    registry = CoreFactory.sessionRegistry(sessionFactory);
    connections = new ConnectionService(repository, mapper, registry);
    sessions =
        new ConnectionSessionService(
            connections,
            mapper,
            new SecureBundleService(cipher, CoreFactory.scbPathResolver()),
            registry,
            holder);
  }

  @AfterEach
  void tearDown() {
    if (registry != null) {
      registry.close();
    }
    if (database != null) {
      database.shutdown();
    }
  }

  @Test
  @DisplayName("save, connect, probe, browse, disconnect - the journey the UI drives")
  void fullLifecycle() {
    ConnectionResponse saved = connections.create(request("it-lifecycle"));

    assertThat(saved.id()).isNotBlank();
    assertThat(saved.hasPassword()).isFalse();
    assertThat(saved.connected()).isFalse();

    SessionState connected = sessions.connect(saved.id());

    assertThat(connected.connected()).isTrue();
    assertThat(connected.sessionId()).startsWith("sess_");
    assertThat(connected.releaseVersion()).startsWith("5.");
    assertThat(connected.idleTimeoutSeconds()).isEqualTo(1800);
    assertThat(connected.capabilities().flavour()).isEqualTo("CASSANDRA");

    // The shared seam three other workstreams code against.
    assertThat(registry.isConnected(saved.id())).isTrue();
    assertThat(registry.session(saved.id()).execute("SELECT release_version FROM system.local").one())
        .isNotNull();
    assertThat(registry.capabilities(saved.id()).supports(Capability.SAI)).isTrue();
    assertThat(registry.capabilities(saved.id()).supports(Capability.VECTOR_ANN)).isTrue();

    // Re-connecting is idempotent, not a second session.
    assertThat(sessions.connect(saved.id()).sessionId()).isEqualTo(connected.sessionId());
    assertThat(sessions.sessions()).hasSize(1);
    assertThat(connections.get(saved.id()).connected()).isTrue();
    assertThat(connections.get(saved.id()).lastConnectedAt()).isNotNull();

    ConnectionHealth health = sessions.health(saved.id());
    assertThat(health.status()).isEqualTo(ConnectionHealth.Status.CONNECTED);
    assertThat(health.nodes()).isNotEmpty();
    assertThat(health.openConnections()).isPositive();

    assertThat(sessions.capabilities(saved.id(), false).releaseVersion()).startsWith("5.");
    assertThat(sessions.capabilities(saved.id(), true).flavour()).isEqualTo("CASSANDRA");

    SessionState disconnected = sessions.disconnect(saved.id());
    assertThat(disconnected.connected()).isFalse();
    assertThat(registry.isConnected(saved.id())).isFalse();
    assertThat(sessions.health(saved.id()).status())
        .isEqualTo(ConnectionHealth.Status.DISCONNECTED);

    connections.delete(saved.id());
    assertThatThrownBy(() -> connections.get(saved.id()))
        .isInstanceOf(ConnectionNotFoundException.class);
  }

  @Test
  @DisplayName("credentials survive a restart because they round-trip through AES-256-GCM in H2")
  void secretsRoundTripThroughTheDatabase() {
    ConnectionResponse saved =
        connections.create(
            new ConnectionRequest(
                "it-secrets",
                ConnectionMode.CASSANDRA,
                null,
                List.of(contactPoint()),
                CassandraSingleton.LOCAL_DATACENTER,
                "cassandra",
                "cassandra",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null));

    ConnectionResponse reloaded = connections.get(saved.id());

    assertThat(reloaded.hasPassword()).isTrue();
    assertThat(reloaded.username()).isEqualTo("cassandra");
    // The credential is not in the response - only the flag is.
    assertThat(reloaded.toString()).doesNotContain("cassandra\"");
  }

  @Test
  void testConnectionProbesWithoutSaving() {
    ConnectionTestResult result = sessions.test(null, request("it-unsaved"));

    assertThat(result.success()).isTrue();
    assertThat(result.releaseVersion()).startsWith("5.");
    assertThat(result.nodeCount()).isPositive();
    assertThat(result.capabilities().bulkFallback()).isEqualTo("TOKEN_RANGE_SCAN");
    assertThat(connections.list()).noneMatch(c -> "it-unsaved".equals(c.name()));
  }

  @Test
  @DisplayName("a failed test is a 200 with success=false and no credential in the problem")
  void testConnectionReportsFailureWithoutLeakingCredentials() {
    ConnectionRequest unreachable =
        new ConnectionRequest(
            "it-unreachable",
            ConnectionMode.CASSANDRA,
            null,
            List.of(new ContactPoint("127.0.0.1", 1)),
            "datacenter1",
            "cassandra",
            "a-very-secret-password",
            null,
            null,
            500,
            null,
            null,
            null,
            null,
            null);

    ConnectionTestResult result = sessions.test(null, unreachable);

    assertThat(result.success()).isFalse();
    assertThat(result.problem()).isNotNull();
    assertThat(result.problem().getDetail()).doesNotContain("a-very-secret-password");
  }

  @Test
  void capabilitiesRequireALiveSession() {
    ConnectionResponse saved = connections.create(request("it-not-connected"));

    assertThatThrownBy(() -> sessions.capabilities(saved.id(), false))
        .isInstanceOf(ConnectionNotOpenException.class);
  }

  @Test
  void duplicateNamesAreRejected() {
    connections.create(request("it-duplicate"));

    assertThatThrownBy(() -> connections.create(request("it-duplicate")))
        .isInstanceOf(DuplicateConnectionNameException.class);
  }

  /* ------------------------------------------------------------------ fixtures */

  private static ConnectionRequest request(String name) {
    return new ConnectionRequest(
        name,
        ConnectionMode.CASSANDRA,
        "Shared Testcontainers Cassandra 5.x",
        List.of(contactPoint()),
        CassandraSingleton.LOCAL_DATACENTER,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        List.of("it"));
  }

  private static ContactPoint contactPoint() {
    InetSocketAddress address = CassandraSingleton.contactPoint();
    return new ContactPoint(address.getHostString(), address.getPort());
  }

  /** Runs the real Flyway migrations, so V2 is exercised rather than a hand-written test schema. */
  private static void migrate(DataSource dataSource) {
    org.flywaydb.core.Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .load()
        .migrate();
  }
}
