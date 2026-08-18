package io.cassyx.core.api;

import io.cassyx.core.api.astra.AstraDevOpsClient;
import io.cassyx.core.api.astra.ScbPathResolver;
import io.cassyx.core.impl.DefaultCqlStatementSplitter;
import io.cassyx.core.impl.DriverSessionFactory;
import io.cassyx.core.impl.MetadataSchemaCatalog;
import io.cassyx.core.impl.PagingQueryExecutor;
import io.cassyx.core.impl.astra.AllowListScbPathResolver;
import io.cassyx.core.impl.astra.HttpAstraDevOpsClient;
import java.nio.file.Path;
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

  public static SchemaCatalog schemaCatalog() {
    return new MetadataSchemaCatalog();
  }

  public static QueryExecutor queryExecutor() {
    return new PagingQueryExecutor();
  }

  public static CqlStatementSplitter statementSplitter() {
    return new DefaultCqlStatementSplitter();
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

  /** All {@link CapabilityProbe} services on the classpath, ordered by priority. */
  public static List<CapabilityProbe> capabilityProbes() {
    List<CapabilityProbe> probes = new ArrayList<>();
    CapabilityProbe.load().forEach(probes::add);
    probes.sort(Comparator.comparingInt(CapabilityProbe::priority));
    return List.copyOf(probes);
  }

  /** Runs the probes in priority order and returns the first match. */
  public static Optional<ClusterCapabilities> detectCapabilities(
      com.datastax.oss.driver.api.core.CqlSession session) {
    for (CapabilityProbe probe : capabilityProbes()) {
      Optional<ClusterCapabilities> result = probe.probe(session);
      if (result.isPresent()) {
        return result;
      }
    }
    return Optional.empty();
  }
}
