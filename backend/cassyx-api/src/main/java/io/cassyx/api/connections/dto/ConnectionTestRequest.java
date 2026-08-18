package io.cassyx.api.connections.dto;

import jakarta.validation.Valid;

/**
 * Test a saved connection by {@code connectionId} (reusing its stored secrets, which the browser
 * does not have) or unsaved form input by {@code connection}. Exactly one.
 */
public record ConnectionTestRequest(String connectionId, @Valid ConnectionRequest connection) {

  public boolean isSaved() {
    return connectionId != null && !connectionId.isBlank();
  }

  @Override
  public String toString() {
    return "ConnectionTestRequest[connectionId=" + connectionId + ", <secrets redacted>]";
  }
}
