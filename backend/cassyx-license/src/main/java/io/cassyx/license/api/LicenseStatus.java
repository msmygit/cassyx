package io.cassyx.license.api;

import java.util.Optional;

/**
 * Result of a verification attempt. Never throws for an invalid license - it is a normal state.
 *
 * <p>{@code state} carries why (plan section 9.4); {@code daysRemaining} is non-null only for
 * time-limited licences and drives the trial countdown in the UI.
 */
public record LicenseStatus(
    boolean valid, String reason, License license, LicenseState state, Long daysRemaining) {

  public static LicenseStatus valid(License license) {
    LicenseState state = license != null && license.isBypass() ? LicenseState.BYPASS
        : LicenseState.VALID;
    return new LicenseStatus(true, null, license, state, null);
  }

  /** A valid, time-limited licence carrying its remaining-days countdown. */
  public static LicenseStatus valid(License license, Long daysRemaining) {
    return new LicenseStatus(true, null, license, LicenseState.VALID, daysRemaining);
  }

  public static LicenseStatus invalid(String reason) {
    return new LicenseStatus(false, reason, null, LicenseState.INVALID_SIGNATURE, null);
  }

  public static LicenseStatus invalid(String reason, LicenseState state) {
    return new LicenseStatus(false, reason, null, state, null);
  }

  /**
   * A genuine licence that has lapsed. The licence is deliberately RETAINED so the UI can greet the
   * buyer by name and pre-fill their email at checkout - the signature was valid, only the clock ran
   * out.
   */
  public static LicenseStatus expired(License license, String reason) {
    return new LicenseStatus(false, reason, license, LicenseState.EXPIRED, 0L);
  }

  /**
   * A genuine, unexpired licence bought for an older major version (plan section 9.5). The licence
   * is retained so the UI can name the covered version and offer the upgrade.
   */
  public static LicenseStatus upgradeRequired(License license, String reason) {
    return new LicenseStatus(false, reason, license, LicenseState.UPGRADE_REQUIRED, null);
  }

  public Optional<License> licenseOpt() {
    return Optional.ofNullable(license);
  }

  public Optional<Long> daysRemainingOpt() {
    return Optional.ofNullable(daysRemaining);
  }

  /** True when the caller should surface the purchase flow rather than an error (plan 9.4). */
  public boolean invitesPurchase() {
    return state != null && state.invitesPurchase();
  }
}
