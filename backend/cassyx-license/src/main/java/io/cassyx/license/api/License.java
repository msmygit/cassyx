package io.cassyx.license.api;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Verified license payload (plan section 9.1).
 *
 * <pre>
 * { "lic":"CSX-XXXX-XXXX-XXXX", "email":"...", "name":"...", "issued":"2026-08-17",
 *   "edition":"standard", "seats":1, "ver":1 }
 * </pre>
 *
 * <p>The payload is versioned via {@code ver} precisely so it can be extended without invalidating
 * keys already in customers' hands. Two such extensions exist, and both are <em>absent-means-
 * unrestricted</em> so that every key minted before they existed keeps verifying unchanged:
 *
 * <ul>
 *   <li>{@code expires} - trial expiry (plan section 9.4). Absent on perpetual licences.
 *   <li>{@code scope} - the purchased major version (plan section 9.5). Absent means every version.
 * </ul>
 */
public record License(
    String lic,
    String email,
    String name,
    LocalDate issued,
    String edition,
    int seats,
    int ver,
    LocalDate expires,
    Integer scope) {

  /** Edition reported when {@code cassyx.license.enforce=false} (plan section 9.2). */
  public static final String BYPASS_EDITION = "unlicensed-bypass";

  public static final String STANDARD_EDITION = "standard";

  /** Time-limited evaluation license (plan section 9.4). Always carries {@link #expires()}. */
  public static final String TRIAL_EDITION = "trial";

  /** Default trial length issued by the licensing service. */
  public static final int DEFAULT_TRIAL_DAYS = 14;

  /** Perpetual, unscoped convenience constructor: no expiry, valid for every major version. */
  public License(
      String lic, String email, String name, LocalDate issued, String edition, int seats, int ver) {
    this(lic, email, name, issued, edition, seats, ver, null, null);
  }

  /** Convenience constructor for a time-limited licence with no version restriction. */
  public License(
      String lic,
      String email,
      String name,
      LocalDate issued,
      String edition,
      int seats,
      int ver,
      LocalDate expires) {
    this(lic, email, name, issued, edition, seats, ver, expires, null);
  }

  /**
   * The synthetic license returned when enforcement is off. The UI keeps its banner visible so a
   * bypassed instance is never mistaken for a paid one.
   */
  public static License bypass() {
    return new License(
        "CSX-BYPASS", null, "License enforcement disabled", LocalDate.now(), BYPASS_EDITION, 0, 1);
  }

  public boolean isBypass() {
    return BYPASS_EDITION.equals(edition);
  }

  public boolean isTrial() {
    return TRIAL_EDITION.equals(edition);
  }

  /** A licence with no {@code expires} never lapses. Paid keys are perpetual by design. */
  public boolean isPerpetual() {
    return expires == null;
  }

  /**
   * Expiry is inclusive: a key with {@code expires=2026-09-01} is valid <em>through</em> the whole
   * of 1 September and lapses on the 2nd. Buyers read "expires on the 1st" as "works on the 1st",
   * and an off-by-one here reads as the product cheating them out of a day.
   */
  public boolean isExpiredOn(LocalDate today) {
    return expires != null && today.isAfter(expires);
  }

  /**
   * Whole days of validity left, inclusive of {@code today}; {@code null} when perpetual. Returns 0
   * on the final day and never goes negative.
   */
  public Long daysRemaining(LocalDate today) {
    if (expires == null) {
      return null;
    }
    return Math.max(0L, ChronoUnit.DAYS.between(today, expires));
  }

  /**
   * Whether this licence covers the running application's major version (plan section 9.5).
   *
   * <p>One-time pricing means upgrade revenue is the only revenue from existing customers, so a key
   * is perpetual for the major version it bought and future majors are a paid upgrade. Crucially the
   * licence never stops working on the version it was sold for - upgrading the app is the customer's
   * choice, so a stale key is a reason to withhold a <em>new</em> release, never to break a running
   * install.
   *
   * <p>{@code scope == null} means unrestricted. That is deliberately the backwards-compatible
   * default, so keys minted before scoping existed keep working everywhere.
   */
  public boolean coversMajor(int appMajor) {
    return scope == null || appMajor <= scope;
  }
}
