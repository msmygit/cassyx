package io.cassyx.license.api;

import io.cassyx.license.impl.Ed25519LicenseVerifier;
import java.util.List;

/**
 * Composition entry point of cassyx-license (plan section 2.1).
 *
 * <pre>{@code
 * LicenseVerifier verifier = LicenseFactory.verifier(System.getenv("CASSYX_LICENSE_PUBLIC_KEY"));
 * LicenseStatus status = LicenseFactory.check(verifier, licenseKey, enforce);
 * }</pre>
 */
public final class LicenseFactory {

  private LicenseFactory() {}

  public static LicenseVerifier verifier(String publicKeyBase64) {
    return new Ed25519LicenseVerifier(publicKeyBase64);
  }

  /**
   * Applies the bypass flag of plan section 9.2: when {@code enforce} is false the check
   * short-circuits to a synthetic valid license reporting edition
   * {@link License#BYPASS_EDITION}. Callers must log a WARN at startup and keep the UI banner up.
   */
  public static LicenseStatus check(LicenseVerifier verifier, String licenseKey, boolean enforce) {
    if (!enforce) {
      return LicenseStatus.valid(License.bypass());
    }
    return verifier.verify(licenseKey);
  }

  public static PaymentProvider paymentProvider(String id) {
    return PaymentProvider.forId(id);
  }

  public static List<PaymentProvider> paymentProviders() {
    return PaymentProvider.available();
  }
}
