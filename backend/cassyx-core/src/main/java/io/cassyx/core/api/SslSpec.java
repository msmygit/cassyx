package io.cassyx.core.api;

import java.util.List;

/**
 * SSL / mTLS material for a connection (plan section 3, NoSQL Manager parity).
 *
 * <p>Stores are carried as bytes rather than paths because they live encrypted in H2, not on disk -
 * the same reasoning as the Astra secure connect bundle. A path-based design forces the store and
 * the server onto the same host and makes the product undeployable.
 *
 * @param hostnameValidation defaults to true. Turning it off is offered because some Cassandra
 *     deployments issue certificates with no matching SAN, but it is a deliberate downgrade and the
 *     UI says so.
 * @param truststore JKS or PKCS12 bytes verifying the server, or null
 * @param keystore client JKS or PKCS12 bytes for mTLS, or null
 */
public record SslSpec(
    boolean enabled,
    boolean hostnameValidation,
    byte[] truststore,
    Secret truststorePassword,
    byte[] keystore,
    Secret keystorePassword,
    List<String> cipherSuites) {

  public SslSpec {
    truststore = truststore == null ? null : truststore.clone();
    keystore = keystore == null ? null : keystore.clone();
    truststorePassword = truststorePassword == null ? Secret.empty() : truststorePassword;
    keystorePassword = keystorePassword == null ? Secret.empty() : keystorePassword;
    cipherSuites = cipherSuites == null ? List.of() : List.copyOf(cipherSuites);
  }

  public static SslSpec disabled() {
    return new SslSpec(false, true, null, Secret.empty(), null, Secret.empty(), List.of());
  }

  @Override
  public byte[] truststore() {
    return truststore == null ? null : truststore.clone();
  }

  @Override
  public byte[] keystore() {
    return keystore == null ? null : keystore.clone();
  }

  public boolean hasTruststore() {
    return truststore != null && truststore.length > 0;
  }

  public boolean hasKeystore() {
    return keystore != null && keystore.length > 0;
  }

  @Override
  public String toString() {
    return "SslSpec[enabled="
        + enabled
        + ", hostnameValidation="
        + hostnameValidation
        + ", hasTruststore="
        + hasTruststore()
        + ", hasKeystore="
        + hasKeystore()
        + "]";
  }
}
