package io.cassyx.api.connections.dto;

import java.time.Instant;
import java.util.List;

/**
 * What drives the UI's connected indicator. Deliberately cheap: it reads driver node state and
 * issues no CQL, so the UI can poll it without adding load to the cluster.
 */
public record ConnectionHealth(
    String connectionId,
    Status status,
    List<NodeHealth> nodes,
    int openConnections,
    int inFlightRequests,
    Instant checkedAt) {

  public ConnectionHealth {
    nodes = nodes == null ? List.of() : List.copyOf(nodes);
  }

  /** {@code DEGRADED} means connected but with at least one node down. */
  public enum Status {
    CONNECTED,
    DEGRADED,
    DISCONNECTED
  }

  public static ConnectionHealth disconnected(String connectionId, Instant at) {
    return new ConnectionHealth(connectionId, Status.DISCONNECTED, List.of(), 0, 0, at);
  }
}
