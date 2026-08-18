package io.cassyx.core.api;

import com.datastax.oss.driver.api.core.CqlSession;
import io.cassyx.core.api.astra.AstraDevOpsClient;
import io.cassyx.core.api.astra.ScbPathResolver;
import io.cassyx.core.impl.AesGcmSecretCipher;
import io.cassyx.core.impl.CachingSessionRegistry;
import io.cassyx.core.impl.DefaultCapabilityProbe;
import io.cassyx.core.impl.DefaultCqlStatementSplitter;
import io.cassyx.core.impl.DriverSessionFactory;
import io.cassyx.core.impl.MetadataSchemaCatalog;
import io.cassyx.core.impl.MinaSshTunnel;
import io.cassyx.core.impl.PagingQueryExecutor;
import io.cassyx.core.impl.astra.AllowListScbPathResolver;
import io.cassyx.core.impl.astra.HttpAstraDevOpsClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * The single composition entry point of cassyx-core.
 *
 * <p>This is why no sibling module - including cassyx-api - ever imports {@code io.cassyx.core.impl}
 * (plan section 2.1, ArchUnit-enforced). Only this class, which lives inside the module, does.
 *
 * <pre>{@code
 * SchemaCatalog catalog = CoreFactory.schemaCatalog();
 * try (CqlSession session = CoreFactory.sessionFactory().open(
 *         ConnectionSpec.cassandra("local", List.of("127.0.0.1:9042"), "datacenter1"))) {
 *   catalog.keyspaces(session, false).forEach(System.out::println);
 * }
 * }</pre>
 */
public final class CoreFactory {

  private CoreFactory() {}

  public static SessionFactory sessionFactory() {
    return new DriverSessionFactory();
  }

  /**
   * @param secureBundleResolver supplies a local secure connect bundle path for Astra specs
   */
  public static SessionFactory sessionFactory(Function<ConnectionSpec, Path> secureBundleResolver) {
    return new DriverSessionFactory(secureBundleResolver);
  }

  /**
   * A session registry with the default 30-minute idle-eviction TTL (plan section 3).
   *
   * <p>There should be exactly ONE of these per process - it is the cache that makes "one
   * {@link CqlSession} per connection, never one per request" true. Two registries means two
   * sessions per connection and double the connection pools against every cluster.
   */
  public static ManagedSessionRegistry sessionRegistry(SessionFactory sessionFactory) {
    return new CachingSessionRegistry(sessionFactory);
  }

  public static ManagedSessionRegistry sessionRegistry(
      SessionFactory sessionFactory, Duration idleTimeout) {
    return new CachingSessionRegistry(sessionFactory, idleTimeout);
  }

  public static SchemaCatalog schemaCatalog() {
    return new MetadataSchemaCatalog();
  }

  public static QueryExecutor queryExecutor() {
    return new PagingQueryExecutor();
  }

  public static CqlStatementSplitter statementSplitter() {
    return new DefaultCqlStatementSplitter();
  }

  /**
   * AES-256-GCM envelope cipher keyed from {@code CASSYX_SECRET_KEY} (plan section 3).
   *
   * @throws SecretCipherException if the variable is unset or is not a 256-bit key. Deliberately
   *     fatal: falling back to plaintext storage of cluster passwords and Astra tokens is not an
   *     acceptable default.
   */
  public static SecretCipher secretCipher() {
    return AesGcmSecretCipher.fromEnvironment();
  }

  /** @param key base64, hex, or raw 32-byte key material */
  public static SecretCipher secretCipher(String key) {
    return AesGcmSecretCipher.fromKeyString(key);
  }

  /** A fresh base64-encoded 256-bit key, for the setup instructions. */
  public static String generateSecretKey() {
    return AesGcmSecretCipher.generateKey();
  }

  /**
   * Opens an SSH local port forward. The caller then points the connection's contact points at
   * {@link SshTunnel#localContactPoint()} and owns closing the tunnel with the session.
   */
  public static SshTunnel openSshTunnel(SshTunnelSpec spec) {
    return MinaSshTunnel.open(spec);
  }

  /** @param token an {@code AstraCS:...} token; never logged by the returned client */
  public static AstraDevOpsClient astraDevOpsClient(Secret token) {
    return new HttpAstraDevOpsClient(token.reveal());
  }

  public static AstraDevOpsClient astraDevOpsClient(Secret token, String baseUrl) {
    return new HttpAstraDevOpsClient(token.reveal(), baseUrl);
  }

  /** Allow-list resolver rooted at {@code CASSYX_SCB_PATH_ROOT} (default {@code /etc/cassyx/scb}). */
  public static ScbPathResolver scbPathResolver() {
    return AllowListScbPathResolver.fromEnvironment();
  }

  public static ScbPathResolver scbPathResolver(Path root) {
    return new AllowListScbPathResolver(root);
  }

  /**
   * Verifies that a file really is an Astra secure connect bundle - a readable zip with the
   * expected entries - so a wrong file fails with a clear message instead of an opaque TLS error
   * at connect time (plan section 3).
   *
   * @throws io.cassyx.core.api.astra.ScbPathException if it is not
   */
  public static void verifySecureConnectBundle(Path file) {
    AllowListScbPathResolver.verifyBundle(file);
  }

  /** All {@link CapabilityProbe} services on the classpath, ordered by priority. */
  public static List<CapabilityProbe> capabilityProbes() {
    List<CapabilityProbe> probes = new ArrayList<>();
    CapabilityProbe.load().forEach(probes::add);
    probes.sort(Comparator.comparingInt(CapabilityProbe::priority));
    return List.copyOf(probes);
  }

  /** Runs the probes in priority order and returns the first match. */
  public static Optional<ClusterCapabilities> detectCapabilities(CqlSession session) {
    return detectCluster(session, null).map(ClusterProbeResult::capabilities);
  }

  /**
   * The full connect-time probe of plan section 7.1.
   *
   * @param hint what the connection settings say the target is; see
   *     {@link CapabilityProbe#probeCluster(CqlSession, ClusterFlavor)}
   */
  public static Optional<ClusterProbeResult> detectCluster(CqlSession session, ClusterFlavor hint) {
    for (CapabilityProbe probe : capabilityProbes()) {
      Optional<ClusterProbeResult> result = probe.probeCluster(session, hint);
      if (result.isPresent()) {
        return result;
      }
    }
    return new DefaultCapabilityProbe().probeCluster(session, hint);
  }
}
