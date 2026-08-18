package io.cassyx.license.impl;

import static org.assertj.core.api.Assertions.assertThat;

import io.cassyx.license.api.License;
import io.cassyx.license.api.LicenseState;
import io.cassyx.license.api.LicenseStatus;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Plan sections 9.4 (trial expiry) and 9.5 (purchased version scope). */
class TrialAndScopeTest {

  private static KeyPair keyPair;

  @BeforeAll
  static void generateKeys() throws Exception {
    keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
  }

  private static String key(String payload) throws Exception {
    Signature signer = Signature.getInstance("Ed25519");
    signer.initSign(keyPair.getPrivate());
    byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
    signer.update(bytes);
    Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
    return enc.encodeToString(bytes) + "." + enc.encodeToString(signer.sign());
  }

  private static String trialPayload(String expires) {
    return "{\"lic\":\"CSX-TRIAL-0001\",\"email\":\"eval@example.com\",\"name\":\"Eval\","
        + "\"issued\":\"2026-08-01\",\"edition\":\"trial\",\"seats\":1,\"ver\":1,"
        + "\"expires\":\"" + expires + "\"}";
  }

  private static Ed25519LicenseVerifier at(String date) {
    return at(date, Ed25519LicenseVerifier.UNSCOPED);
  }

  private static Ed25519LicenseVerifier at(String date, int appMajor) {
    Clock clock = Clock.fixed(Instant.parse(date + "T12:00:00Z"), ZoneOffset.UTC);
    return new Ed25519LicenseVerifier(keyPair.getPublic(), clock, appMajor);
  }

  // ---- trial expiry (9.4) ----

  @Test
  void trialIsValidBeforeExpiryAndCountsDownRemainingDays() throws Exception {
    LicenseStatus status = at("2026-08-18").verify(key(trialPayload("2026-09-01")));

    assertThat(status.valid()).isTrue();
    assertThat(status.state()).isEqualTo(LicenseState.VALID);
    assertThat(status.daysRemaining()).isEqualTo(14L);
    assertThat(status.licenseOpt().orElseThrow().isTrial()).isTrue();
  }

  @Test
  void trialIsStillValidOnItsFinalDay() throws Exception {
    // Expiry is inclusive: "expires 2026-09-01" must still work all day on the 1st.
    LicenseStatus status = at("2026-09-01").verify(key(trialPayload("2026-09-01")));

    assertThat(status.valid()).isTrue();
    assertThat(status.daysRemaining()).isZero();
  }

  @Test
  void trialLapsesTheDayAfterExpiryAndInvitesPurchase() throws Exception {
    LicenseStatus status = at("2026-09-02").verify(key(trialPayload("2026-09-01")));

    assertThat(status.valid()).isFalse();
    assertThat(status.state()).isEqualTo(LicenseState.EXPIRED);
    assertThat(status.reason()).contains("Trial expired on 2026-09-01");
    assertThat(status.invitesPurchase()).isTrue();
    // The licence is retained so checkout can be pre-filled with the evaluator's details.
    assertThat(status.licenseOpt().orElseThrow().email()).isEqualTo("eval@example.com");
  }

  @Test
  void expiryCannotBeExtendedByEditingThePayload() throws Exception {
    String genuine = key(trialPayload("2026-09-01"));
    String forged =
        Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(trialPayload("2099-01-01").getBytes(StandardCharsets.UTF_8))
            + "."
            + genuine.split("\\.")[1];

    LicenseStatus status = at("2026-09-02").verify(forged);

    assertThat(status.valid()).isFalse();
    // Signature is checked BEFORE expiry, so this reads as tampering, not as a lapsed trial.
    assertThat(status.state()).isEqualTo(LicenseState.INVALID_SIGNATURE);
  }

  @Test
  void perpetualLicenceNeverExpiresAndReportsNoCountdown() throws Exception {
    String perpetual =
        "{\"lic\":\"CSX-AAAA\",\"email\":\"a@example.com\",\"name\":\"Ada\","
            + "\"issued\":\"2026-08-17\",\"edition\":\"standard\",\"seats\":1,\"ver\":1}";

    LicenseStatus status = at("2099-12-31").verify(key(perpetual));

    assertThat(status.valid()).isTrue();
    assertThat(status.daysRemaining()).isNull();
    assertThat(status.licenseOpt().orElseThrow().isPerpetual()).isTrue();
  }

  @Test
  void unreadableExpiryFailsClosedRatherThanBecomingPerpetual() throws Exception {
    String bad =
        "{\"lic\":\"CSX-X\",\"edition\":\"trial\",\"ver\":1,\"expires\":\"not-a-date\"}";

    LicenseStatus status = at("2026-08-18").verify(key(bad));

    assertThat(status.valid()).isFalse();
    assertThat(status.state()).isEqualTo(LicenseState.MALFORMED);
  }

  // ---- purchased version scope (9.5) ----

  private static String scopedPayload(int scope) {
    return "{\"lic\":\"CSX-PAID-0001\",\"email\":\"buyer@example.com\",\"name\":\"Buyer\","
        + "\"issued\":\"2026-08-17\",\"edition\":\"standard\",\"seats\":1,\"ver\":1,"
        + "\"scope\":" + scope + "}";
  }

  @Test
  void scopedLicenceCoversThePurchasedMajorVersion() throws Exception {
    assertThat(at("2026-08-18", 1).verify(key(scopedPayload(1))).valid()).isTrue();
  }

  @Test
  void scopedLicenceStillCoversOlderMajors() throws Exception {
    // Bought v2, running v1: fine. Scope is a ceiling, not an equality check.
    assertThat(at("2026-08-18", 1).verify(key(scopedPayload(2))).valid()).isTrue();
  }

  @Test
  void newerMajorRequiresAnUpgradeButNamesWhatWasPurchased() throws Exception {
    LicenseStatus status = at("2026-08-18", 2).verify(key(scopedPayload(1)));

    assertThat(status.valid()).isFalse();
    assertThat(status.state()).isEqualTo(LicenseState.UPGRADE_REQUIRED);
    assertThat(status.invitesPurchase()).isTrue();
    assertThat(status.reason()).contains("cassyx 1.x").contains("2.x");
    assertThat(status.licenseOpt().orElseThrow().scope()).isEqualTo(1);
  }

  @Test
  void unscopedKeysMintedBeforeScopingKeepWorkingOnEveryVersion() throws Exception {
    String legacy =
        "{\"lic\":\"CSX-LEGACY\",\"email\":\"old@example.com\",\"name\":\"Old\","
            + "\"issued\":\"2026-01-01\",\"edition\":\"standard\",\"seats\":1,\"ver\":1}";

    assertThat(at("2026-08-18", 7).verify(key(legacy)).valid()).isTrue();
    assertThat(License.STANDARD_EDITION).isEqualTo("standard");
  }

  @Test
  void expiredTrialIsReportedAsExpiredEvenWhenAlsoOutOfScope() throws Exception {
    String both =
        "{\"lic\":\"CSX-T\",\"edition\":\"trial\",\"seats\":1,\"ver\":1,"
            + "\"expires\":\"2026-09-01\",\"scope\":1}";

    // Expiry is the more actionable message, so it wins the ordering.
    LicenseStatus status = at("2026-09-02", 2).verify(key(both));

    assertThat(status.state()).isEqualTo(LicenseState.EXPIRED);
  }

  @Test
  void trialDefaultsAreDeclared() {
    assertThat(License.TRIAL_EDITION).isEqualTo("trial");
    assertThat(License.DEFAULT_TRIAL_DAYS).isEqualTo(14);

    LocalDate issued = LocalDate.of(2026, 8, 18);
    License trial =
        new License(
            "CSX-T", "e@x.com", "E", issued, License.TRIAL_EDITION, 1, 1,
            issued.plusDays(License.DEFAULT_TRIAL_DAYS));
    assertThat(trial.daysRemaining(issued)).isEqualTo(14L);
    assertThat(trial.isExpiredOn(issued.plusDays(14))).isFalse();
    assertThat(trial.isExpiredOn(issued.plusDays(15))).isTrue();
    assertThat(trial.coversMajor(99)).isTrue();
  }
}
