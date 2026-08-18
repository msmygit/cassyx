package io.cassyx.license.api;

/**
 * Offline Ed25519 license verification (plan section 9.1).
 *
 * <p>The application embeds only the PUBLIC key, so a leaked build reveals nothing that can mint
 * licenses and the product works fully air-gapped - which matters because self-hosted Cassandra
 * clusters frequently have no egress. Minting (which needs the private key) lives in the separate
 * {@code licensing/} deployment, never here.
 */
public interface LicenseVerifier {

  /** Key format: {@code base64url(payloadJson) + "." + base64url(signature)}. */
  LicenseStatus verify(String licenseKey);
}
