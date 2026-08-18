package io.cassyx.api.connections.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Write model for a connection.
 *
 * <p>Every secret field is write-only and is therefore absent from {@link ConnectionResponse} by
 * construction rather than by remembering to strip it.
 *
 * <p>On update, a null secret PRESERVES the stored value and an empty string CLEARS it. That
 * asymmetry is what lets the UI render an edit form it never had the secret for.
 */
public record ConnectionRequest(
    @NotBlank @Size(max = 100) String name,
    @NotNull ConnectionMode mode,
    @Size(max = 1000) String description,
    @Valid List<ContactPoint> contactPoints,
    String localDatacenter,
    String username,
    String password,
    String protocolVersion,
    String defaultKeyspace,
    @Min(100) Integer requestTimeoutMillis,
    AstraSettings astra,
    String advancedConfig,
    SshTunnelConfig ssh,
    SslConfig ssl,
    List<String> tags) {

  public ConnectionRequest {
    contactPoints = contactPoints == null ? List.of() : List.copyOf(contactPoints);
    tags = tags == null ? List.of() : List.copyOf(tags);
  }

  /** Never render a request: it is the one object in the system that holds plaintext secrets. */
  @Override
  public String toString() {
    return "ConnectionRequest[name=" + name + ", mode=" + mode + ", <secrets redacted>]";
  }
}
