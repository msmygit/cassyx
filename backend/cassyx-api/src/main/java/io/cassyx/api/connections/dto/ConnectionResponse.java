package io.cassyx.api.connections.dto;

import java.time.Instant;
import java.util.List;

/**
 * Read model for a connection.
 *
 * <p><b>This record contains no secret values and must never be extended to carry one.</b> Presence
 * is reported with {@code has*} booleans. Every field here is safe in a log line, a browser cache
 * and a support bundle.
 */
public record ConnectionResponse(
    String id,
    String name,
    ConnectionMode mode,
    String description,
    List<ContactPoint> contactPoints,
    String localDatacenter,
    String username,
    boolean hasPassword,
    String protocolVersion,
    String defaultKeyspace,
    Integer requestTimeoutMillis,
    boolean hasAdvancedConfig,
    AstraInfo astra,
    SshTunnelInfo ssh,
    SslInfo ssl,
    List<String> tags,
    boolean connected,
    Instant createdAt,
    Instant updatedAt,
    Instant lastConnectedAt) {

  public ConnectionResponse {
    contactPoints = contactPoints == null ? List.of() : List.copyOf(contactPoints);
    tags = tags == null ? List.of() : List.copyOf(tags);
  }
}
