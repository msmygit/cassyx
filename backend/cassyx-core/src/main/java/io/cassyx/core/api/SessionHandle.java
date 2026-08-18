package io.cassyx.core.api;

import java.time.Instant;

/**
 * A live session's metadata, for {@code GET /api/sessions} and the connected indicator.
 *
 * <p>Carries no {@link com.datastax.oss.driver.api.core.CqlSession} - handing the session object
 * out through a listing would let any caller close a session somebody else is querying through.
 *
 * @param sessionId correlates a UI action with a log line; not a security token
 * @param expiresAt when idle eviction will close this session if nothing touches it
 */
public record SessionHandle(
    String connectionId,
    String connectionName,
    String sessionId,
    Instant connectedAt,
    Instant lastAccessAt,
    Instant expiresAt,
    boolean sshTunnelActive,
    ClusterProbeResult probe) {

  public boolean connected() {
    return true;
  }

  public ClusterCapabilities capabilities() {
    return probe == null ? ClusterCapabilities.unknown() : probe.capabilities();
  }
}
