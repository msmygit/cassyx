package io.cassyx.core.api;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * The write side of {@link SessionRegistry}: opening, closing and listing sessions.
 *
 * <p>Split from the read side deliberately. Feature workstreams take a {@code SessionRegistry} and
 * therefore <i>cannot</i> close a session another feature is mid-query on, or re-open one with
 * different credentials. Only the connections layer holds this interface.
 */
public interface ManagedSessionRegistry extends SessionRegistry, AutoCloseable {

  /**
   * Opens a session for {@code connectionId} and probes its capabilities, or returns the existing
   * handle if one is already open.
   *
   * <p><b>Idempotent</b>, because the contract says so: {@code POST /connect} on an already
   * connected connection returns the existing session state rather than churning a new one.
   *
   * @param tunnel an already-established SSH tunnel whose lifetime is now owned by the registry and
   *     closed with the session, or null
   * @throws CassyxCoreException if the cluster cannot be reached or authentication fails
   */
  SessionHandle open(String connectionId, ConnectionSpec spec, SshTunnel tunnel);

  default SessionHandle open(String connectionId, ConnectionSpec spec) {
    return open(connectionId, spec, null);
  }

  /**
   * Closes the session, its SSH tunnel and any materialized secret files.
   *
   * @return true if something was closed, false if there was nothing open. Never throws for an
   *     unknown id: disconnecting an already-disconnected connection is a no-op, not an error.
   */
  boolean close(String connectionId);

  /** Metadata for one live session. */
  Optional<SessionHandle> handle(String connectionId);

  /** Every live session. Multiple simultaneous cluster connections are supported (plan section 3). */
  List<SessionHandle> handles();

  /** The full probe result, richer than {@link #capabilities(String)}. */
  Optional<ClusterProbeResult> probe(String connectionId);

  /** Re-runs the capability probe, for {@code GET /capabilities?refresh=true}. */
  ClusterProbeResult reprobe(String connectionId);

  /** The configured idle-eviction TTL. */
  Duration idleTimeout();

  /** Closes every session. Idempotent. */
  @Override
  void close();
}
