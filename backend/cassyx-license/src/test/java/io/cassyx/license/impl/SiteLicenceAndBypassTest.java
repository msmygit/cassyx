package io.cassyx.license.impl;

import static org.assertj.core.api.Assertions.assertThat;

import io.cassyx.license.api.BypassPolicy;
import io.cassyx.license.api.License;
import io.cassyx.license.api.LicenseFactory;
import io.cassyx.license.api.LicenseState;
import io.cassyx.license.api.LicenseStatus;
import io.cassyx.license.api.LicenseVerifier;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Plan section 9.2: the signed {@code site} edition, and the build-time gate that stops the free
 * {@code CASSYX_LICENSE_ENFORCE=false} switch from being the way everybody "buys" the product.
 *
 * <p>A bypass bug is a revenue bug, which is why this module carries the 90% gate (section 11.1).
 */
class SiteLicenceAndBypassTest {

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

  private static Ed25519LicenseVerifier at(String date) {
    return at(date, Ed25519LicenseVerifier.UNSCOPED);
  }

  private static Ed25519LicenseVerifier at(String date, int appMajor) {
    Clock clock = Clock.fixed(Instant.parse(date + "T12:00:00Z"), ZoneOffset.UTC);
    return new Ed25519LicenseVerifier(keyPair.getPublic(), clock, appMajor);
  }

  private static String sitePayload(String extras) {
    return "{\"lic\":\"CSX-SITE-0001\",\"email\":\"ops@example.com\",\"name\":\"Example Inc\","
        + "\"issued\":\"2026-08-17\",\"edition\":\"site\",\"seats\":0,\"ver\":1"
        + extras
        + "}";
  }

  // ---- the site edition is an ordinary signed licence (9.2) ----

  @Test
  void siteLicenceVerifiesThroughTheSamePathAndIsNotABypass() throws Exception {
    LicenseStatus status = at("2026-08-18").verify(key(sitePayload("")));

    assertThat(status.valid()).isTrue();
    assertThat(status.state()).isEqualTo(LicenseState.VALID);
    License licence = status.licenseOpt().orElseThrow();
    assertThat(licence.isSite()).isTrue();
    // It was GRANTED, so it must not be confused with the unlicensed-bypass sentinel.
    assertThat(licence.isBypass()).isFalse();
    assertThat(licence.edition()).isEqualTo(License.SITE_EDITION);
    // Unlimited seats: 0 means "not counted", not "no seats".
    assertThat(licence.seats()).isZero();
    assertThat(licence.isPerpetual()).isTrue();
    assertThat(status.daysRemaining()).isNull();
  }

  @Test
  void siteLicenceCannotBeMintedWithoutThePrivateKey() throws Exception {
    // The whole point of the edition being just a string in the payload: forging it needs the key.
    String genuine = key(sitePayload(""));
    String forged =
        Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                    sitePayload("").replace("Example Inc", "Freeloader").getBytes(StandardCharsets.UTF_8))
            + "."
            + genuine.split("\\.")[1];

    assertThat(at("2026-08-18").verify(forged).state()).isEqualTo(LicenseState.INVALID_SIGNATURE);
  }

  @Test
  void timeBoxedEvaluationSiteLicenceStillExpires() throws Exception {
    String bounded = key(sitePayload(",\"expires\":\"2026-09-01\""));

    assertThat(at("2026-09-01").verify(bounded).valid()).isTrue();
    assertThat(at("2026-09-01").verify(bounded).daysRemaining()).isZero();

    LicenseStatus lapsed = at("2026-09-02").verify(bounded);
    assertThat(lapsed.valid()).isFalse();
    assertThat(lapsed.state()).isEqualTo(LicenseState.EXPIRED);
    // Not a trial, so the message must not call it one.
    assertThat(lapsed.reason()).isEqualTo("License expired on 2026-09-01");
  }

  @Test
  void scopedSiteLicenceStillNeedsAnUpgradeForANewerMajor() throws Exception {
    String scoped = key(sitePayload(",\"scope\":1"));

    assertThat(at("2026-08-18", 1).verify(scoped).valid()).isTrue();
    assertThat(at("2026-08-18", 2).verify(scoped).state())
        .isEqualTo(LicenseState.UPGRADE_REQUIRED);
  }

  // ---- the build-time bypass gate (9.2) ----

  private static final LicenseVerifier ALWAYS_ABSENT =
      k -> LicenseStatus.invalid("No license key configured", LicenseState.ABSENT);

  @Test
  void bypassIsHonouredWhenTheBuildAllowsIt() {
    LicenseStatus status = LicenseFactory.check(ALWAYS_ABSENT, null, false, true);

    assertThat(status.valid()).isTrue();
    assertThat(status.state()).isEqualTo(LicenseState.BYPASS);
    assertThat(status.licenseOpt().orElseThrow().edition()).isEqualTo(License.BYPASS_EDITION);
  }

  @Test
  void bypassIsRefusedInAReleaseBuildAndVerificationHappensAnyway() {
    LicenseStatus status = LicenseFactory.check(ALWAYS_ABSENT, null, false, false);

    assertThat(status.valid()).isFalse();
    // Falls through to the normal unlicensed state, which still offers activation and purchase.
    assertThat(status.state()).isEqualTo(LicenseState.ABSENT);
  }

  @Test
  void aSiteLicenceUnlocksAReleaseBuildWithNoFlagAtAll() throws Exception {
    LicenseStatus status =
        LicenseFactory.check(at("2026-08-18"), key(sitePayload("")), true, false);

    assertThat(status.valid()).isTrue();
    assertThat(status.licenseOpt().orElseThrow().isSite()).isTrue();
  }

  @Test
  void theLegacyThreeArgCheckStillMeansBypassAllowed() {
    assertThat(LicenseFactory.check(ALWAYS_ABSENT, null, false).state())
        .isEqualTo(LicenseState.BYPASS);
    assertThat(LicenseFactory.check(ALWAYS_ABSENT, null, true).state())
        .isEqualTo(LicenseState.ABSENT);
  }

  @Test
  void policyDistinguishesGrantedRefusedAndNeverAsked() {
    BypassPolicy granted = BypassPolicy.of(false, true);
    assertThat(granted.requested()).isTrue();
    assertThat(granted.granted()).isTrue();
    assertThat(granted.refused()).isFalse();
    assertThat(granted.enforcing()).isFalse();

    BypassPolicy refused = BypassPolicy.of(false, false);
    assertThat(refused.granted()).isFalse();
    assertThat(refused.refused()).isTrue();
    // The flag was ignored, so enforcement stays ON - this is the property the API reports.
    assertThat(refused.enforcing()).isTrue();

    for (boolean allowed : new boolean[] {true, false}) {
      BypassPolicy neverAsked = BypassPolicy.of(true, allowed);
      assertThat(neverAsked.requested()).isFalse();
      assertThat(neverAsked.granted()).isFalse();
      assertThat(neverAsked.refused()).isFalse();
      assertThat(neverAsked.enforcing()).isTrue();
    }
  }

  @Test
  void refusalWarningNamesTheEnvVarTheOperatorActuallySet() {
    String warning = BypassPolicy.of(false, false).refusalWarning();

    assertThat(warning).contains("CASSYX_LICENSE_ENFORCE=false").contains("IGNORED");
    assertThat(warning).contains(BypassPolicy.BYPASS_ALLOWED_PROPERTY);
    // And points at the supported alternative rather than leaving them stuck.
    assertThat(warning).contains(License.SITE_EDITION).contains("CASSYX_LICENSE_KEY");
  }
}
