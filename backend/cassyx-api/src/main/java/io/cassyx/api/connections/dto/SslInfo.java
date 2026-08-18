package io.cassyx.api.connections.dto;

import java.util.List;

/** Response-side view of the SSL configuration - presence flags and file names only. */
public record SslInfo(
    boolean enabled,
    boolean hostnameValidation,
    boolean hasTruststore,
    String truststoreFileName,
    boolean hasTruststorePassword,
    boolean hasKeystore,
    String keystoreFileName,
    boolean hasKeystorePassword,
    List<String> cipherSuites) {

  public SslInfo {
    cipherSuites = cipherSuites == null ? List.of() : List.copyOf(cipherSuites);
  }

  public static SslInfo disabled() {
    return new SslInfo(false, true, false, null, false, false, null, false, List.of());
  }
}
