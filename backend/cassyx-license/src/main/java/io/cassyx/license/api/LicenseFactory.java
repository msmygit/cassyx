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

  /**
   * Application major version sentinel meaning "do not check {@code scope} at all" (plan section
   * 9.5). Callers that cannot determine their own version must pass this rather than a plausible
   * integer: a wrong major turns an upgrade question into a wrong answer in one direction or the
   * other.
   */
  public static final int UNSCOPED = Ed25519LicenseVerifier.UNSCOPED;

  private LicenseFactory() {}

  public static LicenseVerifier verifier(String publicKeyBase64) {
    return new Ed25519LicenseVerifier(publicKeyBase64);
  }

  /**
   * Verifier that also enforces the purchased major version (plan section 9.5), reporting {@link
   * LicenseState#UPGRADE_REQUIRED} for a genuine key bought for an older major. Pass {@link
   * #UNSCOPED} to skip that check.
   *
   * <p>Exists because the scope-aware constructor lives in the implementation package, which
   * cassyx-api is forbidden to import (plan section 2.1, ArchUnit-enforced).
   */
  public static LicenseVerifier verifier(String publicKeyBase64, int appMajor) {
    return new Ed25519LicenseVerifier(
        Ed25519LicenseVerifier.decodePublicKey(publicKeyBase64),
        java.time.Clock.system(java.time.ZoneOffset.UTC),
        appMajor);
  }

  /**
   * Applies the bypass flag of plan section 9.2 in a build that permits it. Kept as the
   * single-argument form for callers with no build-time gate of their own; new code should pass
   * {@code bypassAllowed} explicitly.
   */
  public static LicenseStatus check(LicenseVerifier verifier, String licenseKey, boolean enforce) {
    return check(verifier, licenseKey, enforce, true);
  }

  /**
   * Applies the bypass flag of plan section 9.2, subject to the build-time gate: when {@code
   * enforce} is false AND this build allows the bypass, the check short-circuits to a synthetic
   * valid license reporting edition {@link License#BYPASS_EDITION}. Callers must log a WARN at
   * startup and keep the UI banner up.
   *
   * <p>When the bypass is requested but not allowed we fall through to real verification rather
   * than failing: refusing the switch must leave the product in its normal unlicensed state, which
   * still offers activation and purchase. The refusal itself is logged by the caller (it belongs at
   * startup, once, not on every request).
   */
  public static LicenseStatus check(
      LicenseVerifier verifier, String licenseKey, boolean enforce, boolean bypassAllowed) {
    if (BypassPolicy.of(enforce, bypassAllowed).granted()) {
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
