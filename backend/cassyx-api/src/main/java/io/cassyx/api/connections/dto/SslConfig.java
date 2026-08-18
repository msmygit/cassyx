package io.cassyx.api.connections.dto;

import java.util.List;

/**
 * Write model for SSL / mTLS. The stores themselves arrive through their own multipart endpoints;
 * only their passwords live here, and they are write-only.
 */
public record SslConfig(
    Boolean enabled,
    Boolean hostnameValidation,
    String truststorePassword,
    String keystorePassword,
    List<String> cipherSuites) {

  public boolean isEnabled() {
    return Boolean.TRUE.equals(enabled);
  }

  /** Defaults to true. Turning it off is a deliberate downgrade and the UI labels it as one. */
  public boolean hostnameValidationOrDefault() {
    return !Boolean.FALSE.equals(hostnameValidation);
  }

  public static SslConfig disabled() {
    return new SslConfig(false, true, null, null, List.of());
  }
}
