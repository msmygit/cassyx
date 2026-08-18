package io.cassyx.api.connections;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.metadata.Node;
import io.cassyx.api.connections.dto.ClusterCapabilitiesView;
import io.cassyx.api.connections.dto.ConnectionHealth;
import io.cassyx.api.connections.dto.ConnectionRequest;
import io.cassyx.api.connections.dto.ConnectionTestResult;
import io.cassyx.api.connections.dto.NodeHealth;
import io.cassyx.api.connections.dto.SessionState;
import io.cassyx.core.api.CassyxCoreException;
import io.cassyx.core.api.ClusterProbeResult;
import io.cassyx.core.api.ConnectionSpec;
import io.cassyx.core.api.CoreFactory;
import io.cassyx.core.api.ManagedSessionRegistry;
import io.cassyx.core.api.SessionFactory;
import io.cassyx.core.api.SessionHandle;
import io.cassyx.core.api.SshTunnel;
import io.cassyx.core.api.SshTunnelSpec;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;

/**
 * Session lifecycle for a saved connection: SSH tunnel, then {@code CqlSession}, then the capability
 * probe (plan sections 3 and 7.1).
 *
 * <p>The order is not incidental. The tunnel must be up before the driver resolves a contact point,
 * because the driver has no idea SSH exists - it is simply pointed at {@code 127.0.0.1:<localPort>}
 * instead of at the cluster. And the secure connect bundle is materialised only for the moment the
 * session is being built, then deleted, so a decrypted credential never outlives its use.
 */
@Service
public class ConnectionSessionService {

  private static final Logger LOG = LoggerFactory.getLogger(ConnectionSessionService.class);

  private final ConnectionService connections;
  private final ConnectionMapper mapper;
  private final SecureBundleService bundles;
  private final ManagedSessionRegistry registry;
  private final SecureBundleHolder bundleHolder;
  private final Function<SshTunnelSpec, SshTunnel> tunnelFactory;
  private final Clock clock;

  @Autowired
  public ConnectionSessionService(
      ConnectionService connections,
      ConnectionMapper mapper,
      SecureBundleService bundles,
      ManagedSessionRegistry registry,
      SecureBundleHolder bundleHolder) {
    this(
        connections,
        mapper,
        bundles,
        registry,
        bundleHolder,
        CoreFactory::openSshTunnel,
        Clock.systemUTC());
  }

  ConnectionSessionService(
      ConnectionService connections,
      ConnectionMapper mapper,
      SecureBundleService bundles,
      ManagedSessionRegistry registry,
      SecureBundleHolder bundleHolder,
      Function<SshTunnelSpec, SshTunnel> tunnelFactory,
      Clock clock) {
    this.connections = connections;
    this.mapper = mapper;
    this.bundles = bundles;
    this.registry = registry;
    this.bundleHolder = bundleHolder;
    this.tunnelFactory = tunnelFactory;
    this.clock = clock;
  }

  /* ------------------------------------------------------------------ connect */

  /** Idempotent: connecting an already-connected connection returns the existing session state. */
  public SessionState connect(String connectionId) {
    ConnectionRow row = connections.require(connectionId);
    Optional<SessionHandle> existing = registry.handle(connectionId);
    if (existing.isPresent()) {
      return SessionState.from(existing.get(), registry.idleTimeout());
    }

    ConnectionSpec spec = mapper.toSpec(row);
    SshTunnel tunnel = null;
    Path bundle = null;
    try {
      if (spec.sshTunnel().isPresent()) {
        tunnel = tunnelFactory.apply(spec.sshTunnel().orElseThrow());
        spec = spec.withContactPoints(List.of(tunnel.localContactPoint()));
      }
      bundle = bundles.materialize(row);
      bundleHolder.set(bundle);
      SessionHandle handle = registry.open(connectionId, spec, tunnel);
      tunnel = null; // the registry owns it now and closes it with the session
      connections.touchLastConnected(connectionId);
      return SessionState.from(handle, registry.idleTimeout());
    } catch (RuntimeException e) {
      if (tunnel != null) {
        tunnel.close();
      }
      throw e;
    } finally {
      bundleHolder.clear();
      // A decrypted bundle on disk is a plaintext credential: it lives only as long as the build.
      bundles.discard(row, bundle);
    }
  }

  /** Disconnecting something already disconnected is a no-op, not an error. */
  public SessionState disconnect(String connectionId) {
    ConnectionRow row = connections.require(connectionId);
    registry.close(connectionId);
    return SessionState.disconnected(connectionId, row.name());
  }

  public List<SessionState> sessions() {
    return registry.handles().stream()
        .map(handle -> SessionState.from(handle, registry.idleTimeout()))
        .toList();
  }

  /* ------------------------------------------------------------------ capabilities */

  /** @param refresh re-probe instead of returning the result cached at connect time */
  public ClusterCapabilitiesView capabilities(String connectionId, boolean refresh) {
    connections.require(connectionId);
    ClusterProbeResult probe =
        refresh
            ? registry.reprobe(connectionId)
            : registry
                .probe(connectionId)
                .orElseThrow(
                    () -> new io.cassyx.core.api.ConnectionNotOpenException(connectionId));
    return ClusterCapabilitiesView.from(probe);
  }

  /* ------------------------------------------------------------------ health */

  /** Cheap by design: driver node state only, no CQL, so the UI can poll it. */
  public ConnectionHealth health(String connectionId) {
    ConnectionRow row = connections.require(connectionId);
    Instant now = clock.instant();
    if (!registry.isConnected(connectionId)) {
      return ConnectionHealth.disconnected(row.id(), now);
    }
    CqlSession session = registry.session(connectionId);
    List<NodeHealth> nodes = new ArrayList<>();
    int openConnections = 0;
    int up = 0;
    for (Node node : session.getMetadata().getNodes().values()) {
      openConnections += node.getOpenConnections();
      String state = node.getState() == null ? "UNKNOWN" : node.getState().name();
      if ("UP".equals(state)) {
        up++;
      }
      nodes.add(
          new NodeHealth(
              String.valueOf(node.getEndPoint()),
              state,
              node.getDatacenter(),
              node.getRack(),
              node.getCassandraVersion() == null ? null : node.getCassandraVersion().toString(),
              node.getHostId() == null ? null : node.getHostId().toString(),
              node.getOpenConnections()));
    }
    ConnectionHealth.Status status =
        nodes.isEmpty() || up == 0
            ? ConnectionHealth.Status.DISCONNECTED
            : up == nodes.size() ? ConnectionHealth.Status.CONNECTED : ConnectionHealth.Status.DEGRADED;
    return new ConnectionHealth(row.id(), status, nodes, openConnections, 0, now);
  }

  /* ------------------------------------------------------------------ test */

  /**
   * Builds a throwaway session, reads {@code system.local}, then closes it.
   *
   * <p>A failed probe is still a {@code 200} with {@code success: false}: a Test button whose
   * diagnostics get swallowed by the browser's generic error path is worse than no Test button.
   * The problem document it returns never carries a credential.
   */
  public ConnectionTestResult test(String connectionId, ConnectionRequest inline) {
    long started = System.nanoTime();
    ConnectionRow row;
    if (connectionId != null && !connectionId.isBlank()) {
      row = connections.require(connectionId);
    } else {
      // A throwaway row: the request's own secrets, never persisted.
      row = mapper.apply(inline, new ConnectionRow().id("test-" + UUID.randomUUID()));
    }

    SshTunnel tunnel = null;
    Path bundle = null;
    CqlSession session = null;
    try {
      ConnectionSpec spec = mapper.toSpec(row);
      if (spec.sshTunnel().isPresent()) {
        tunnel = tunnelFactory.apply(spec.sshTunnel().orElseThrow());
        spec = spec.withContactPoints(List.of(tunnel.localContactPoint()));
      }
      bundle = bundles.materialize(row);
      SessionFactory factory = CoreFactory.sessionFactory(withBundlePath(bundle));
      session = factory.open(spec);
      ClusterProbeResult probe =
          CoreFactory.detectCluster(session, mapper.flavorHint(row))
              .orElseGet(ClusterProbeResult::unknown);
      return new ConnectionTestResult(
          true,
          elapsedMillis(started),
          probe.clusterName(),
          probe.releaseVersion(),
          probe.partitioner(),
          probe.datacenters(),
          probe.nodeCount(),
          probe.protocolVersion(),
          ClusterCapabilitiesView.from(probe),
          null);
    } catch (RuntimeException e) {
      LOG.info("Connection test for '{}' failed: {}", row.name(), e.getClass().getSimpleName());
      return ConnectionTestResult.failed(elapsedMillis(started), toProblem(e));
    } finally {
      if (session != null) {
        session.close();
      }
      if (tunnel != null) {
        tunnel.close();
      }
      bundles.discard(row, bundle);
    }
  }

  private static long elapsedMillis(long startedNanos) {
    return (System.nanoTime() - startedNanos) / 1_000_000;
  }

  /**
   * Builds the failure document.
   *
   * <p>Driver exception messages are safe here - they name hosts, ports and auth failures, not
   * credentials - but {@link ConnectionSpec#toString()} redacts and no request body is echoed, so
   * there is no path by which a password reaches this text.
   */
  private static ProblemDetail toProblem(RuntimeException e) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(
            org.springframework.http.HttpStatus.BAD_GATEWAY, describe(e));
    problem.setType(java.net.URI.create("https://cassyx.dev/problems/connection-failed"));
    problem.setTitle("Could not reach cluster");
    return problem;
  }

  private static String describe(RuntimeException e) {
    String message = e.getMessage();
    if (message == null || message.isBlank()) {
      return e.getClass().getSimpleName();
    }
    return e instanceof CassyxCoreException ? message : e.getClass().getSimpleName() + ": " + message;
  }

  private static Function<ConnectionSpec, Path> withBundlePath(Path bundle) {
    return spec -> bundle;
  }
}
