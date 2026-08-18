package io.cassyx.license.api;

/**
 * Why a licence is (in)valid. The UI needs this distinction, not just a boolean: an expired trial
 * deserves a "buy now" call to action, while a bad signature deserves an error. Collapsing both into
 * {@code valid=false} loses the only conversion moment the product gets (plan section 9.4).
 */
public enum LicenseState {

  /** Signature checks out and the licence has not lapsed. */
  VALID,

  /** Enforcement is disabled via {@code cassyx.license.enforce=false} (plan section 9.2). */
  BYPASS,

  /** Genuine, correctly signed - but past its {@code expires} date. Prompt to purchase. */
  EXPIRED,

  /** No key supplied at all. First run: offer a trial. */
  ABSENT,

  /** Present but not parseable as {@code base64url(payload).base64url(signature)}. */
  MALFORMED,

  /** Well-formed but the signature does not verify against the embedded public key. */
  INVALID_SIGNATURE,

  /**
   * Genuine and unexpired, but bought for an older major version than the one running (plan section
   * 9.5). The remedy is a paid upgrade - or simply running the version they paid for.
   */
  UPGRADE_REQUIRED;

  /** True when the right response is to show the purchase flow rather than an error. */
  public boolean invitesPurchase() {
    return this == EXPIRED || this == ABSENT || this == UPGRADE_REQUIRED;
  }
}
