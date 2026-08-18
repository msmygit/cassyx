package io.cassyx.api.connections.dto;

import io.cassyx.core.api.SessionHandle;
import java.time.Duration;
import java.time.Instant;

/** A live session, as {@code POST /connect}, {@code POST /disconnect} and {@code GET /sessions}. */
public record SessionState(
    String connectionId,
    String connectionName,
    boolean connected,
    String sessionId,
    String clusterName,
    String releaseVersion,
    Instant connectedAt,
    Long idleTimeoutSeconds,
    Instant expiresAt,
    boolean sshTunnelActive,
    ClusterCapabilitiesView capabilities) {

  public static SessionState from(SessionHandle handle, Duration idleTimeout) {
    return new SessionState(
        handle.connectionId(),
        handle.connectionName(),
        true,
        handle.sessionId(),
        handle.probe() == null ? null : handle.probe().clusterName(),
        handle.probe() == null ? null : handle.probe().releaseVersion(),
        handle.connectedAt(),
        idleTimeout == null ? null : idleTimeout.toSeconds(),
        handle.expiresAt(),
        handle.sshTunnelActive(),
        handle.probe() == null ? null : ClusterCapabilitiesView.from(handle.probe()));
  }

  /** The shape returned after a disconnect, and for a connection that was never connected. */
  public static SessionState disconnected(String connectionId, String connectionName) {
    return new SessionState(
        connectionId, connectionName, false, null, null, null, null, null, null, false, null);
  }
}
