package io.cassyx.api.license;

import static org.assertj.core.api.Assertions.assertThat;

import io.cassyx.api.license.LicenseController.LicenseStatusResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.LocalDate;
import java.util.Base64;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@code GET /api/license} must stay internally consistent across every combination of (enforce
 * flag, build-time bypass gate, key present/absent/valid) - plan section 9.2.
 *
 * <p>The tuple matters more than any single field: a client that reads {@code enforce=false} and
 * unlocks its UI over an instance that is in fact verifying licences is a bug in exactly the
 * direction that costs revenue, and so is the reverse. The controller takes plain constructor
 * arguments, so the whole matrix is exercised without a Spring context.
 */
class LicenseReportingTest {

  private static KeyPair keyPair;
  private static String publicKey;

  @BeforeAll
  static void generateKeys() throws Exception {
    keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    publicKey = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
  }

  private static String signed(String payload) throws Exception {
    Signature signer = Signature.getInstance("Ed25519");
    signer.initSign(keyPair.getPrivate());
    byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
    signer.update(bytes);
    Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
    return enc.encodeToString(bytes) + "." + enc.encodeToString(signer.sign());
  }

  private static String sitePayload(String extras) {
    return "{\"lic\":\"CSX-SITE-0001\",\"email\":\"ops@example.com\",\"name\":\"Example Inc\","
        + "\"issued\":\"2026-08-17\",\"edition\":\"site\",\"seats\":0,\"ver\":1"
        + extras
        + "}";
  }

  private static LicenseStatusResponse report(
      String pubKey, String key, boolean enforce, boolean bypassAllowed) {
    return new LicenseController(pubKey, key, enforce, bypassAllowed, "0.1.0-SNAPSHOT").current();
  }

  // ---- bypass requested ----

  @Test
  void devBuildHonoursTheFlagAndSaysSoInEveryField() {
    LicenseStatusResponse body = report(publicKey, "", false, true);

    assertThat(body.licensed()).isTrue();
    assertThat(body.enforce()).isFalse();
    assertThat(body.bypass()).isTrue();
    assertThat(body.edition()).isEqualTo("unlicensed-bypass");
    assertThat(body.state()).isEqualTo("BYPASS");
  }

  @Test
  void releaseBuildRefusesTheFlagAndNeverClaimsToBeBypassed() {
    LicenseStatusResponse body = report(publicKey, "", false, false);

    assertThat(body.licensed()).isFalse();
    // The EFFECTIVE value: the flag said false, the build ignored it, licences are being checked.
    assertThat(body.enforce()).isTrue();
    assertThat(body.bypass()).isFalse();
    assertThat(body.edition()).isEqualTo("none");
    assertThat(body.state()).isEqualTo("ABSENT");
  }

  @Test
  void releaseBuildWithNoPublicKeyPointsAtASiteLicenceRatherThanAFlagItIgnores() {
    LicenseStatusResponse body = report("PLACEHOLDER", "", false, false);

    assertThat(body.licensed()).isFalse();
    assertThat(body.enforce()).isTrue();
    assertThat(body.bypass()).isFalse();
    assertThat(body.state()).isEqualTo("MALFORMED");
    assertThat(body.message()).contains("site licence").contains("ignored in this build");
    assertThat(body.message()).doesNotContain("set CASSYX_LICENSE_ENFORCE=false to run unlocked");
  }

  @Test
  void devBuildWithNoPublicKeyStillOffersTheFlagBecauseItWorksHere() {
    LicenseStatusResponse body = report("PLACEHOLDER", "", true, true);

    assertThat(body.state()).isEqualTo("MALFORMED");
    assertThat(body.message()).contains("CASSYX_LICENSE_ENFORCE=false");
  }

  // ---- signed site licence: works in every build, no flag involved ----

  @Test
  void siteLicenceIsReportedAsGrantedNotAsBypassed() throws Exception {
    LicenseStatusResponse body = report(publicKey, signed(sitePayload("")), true, false);

    assertThat(body.licensed()).isTrue();
    assertThat(body.enforce()).isTrue();
    assertThat(body.bypass()).isFalse();
    assertThat(body.edition()).isEqualTo("site");
    assertThat(body.state()).isEqualTo("VALID");
    assertThat(body.trial()).isFalse();
    assertThat(body.seats()).isZero();
    assertThat(body.name()).isEqualTo("Example Inc");
  }

  @Test
  void siteLicenceUnlocksAReleaseBuildEvenWhenTheFlagWasAlsoSet() throws Exception {
    LicenseStatusResponse body = report(publicKey, signed(sitePayload("")), false, false);

    assertThat(body.licensed()).isTrue();
    assertThat(body.bypass()).isFalse();
    assertThat(body.edition()).isEqualTo("site");
  }

  @Test
  void grantedBypassOutranksAConfiguredKeyInADevBuild() throws Exception {
    // The flag is a blunt instrument by design; it must not half-apply.
    LicenseStatusResponse body = report(publicKey, signed(sitePayload("")), false, true);

    assertThat(body.edition()).isEqualTo("unlicensed-bypass");
    assertThat(body.bypass()).isTrue();
  }

  @Test
  void expiredEvaluationSiteLicenceLocksAndKeepsTheEditionVisible() throws Exception {
    String expired = signed(sitePayload(",\"expires\":\"2020-01-01\""));

    LicenseStatusResponse body = report(publicKey, expired, true, false);

    assertThat(body.licensed()).isFalse();
    assertThat(body.state()).isEqualTo("EXPIRED");
    assertThat(body.edition()).isEqualTo("site");
    assertThat(body.expires()).isEqualTo("2020-01-01");
    assertThat(body.email()).isEqualTo("ops@example.com");
  }

  @Test
  void tamperedSiteLicenceIsRejectedInBothBuilds() throws Exception {
    String forged =
        Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(sitePayload("").getBytes(StandardCharsets.UTF_8))
            + ".AAAA";

    for (boolean bypassAllowed : new boolean[] {true, false}) {
      LicenseStatusResponse body = report(publicKey, forged, true, bypassAllowed);
      assertThat(body.licensed()).isFalse();
      assertThat(body.bypass()).isFalse();
      assertThat(body.edition()).isEqualTo("none");
    }
  }

  @Test
  void activationRejectsABadKeyWithAnUnprocessableEntityInAReleaseBuild() {
    LicenseController controller =
        new LicenseController(publicKey, "", false, false, "0.1.0-SNAPSHOT");

    assertThat(controller.activate(new LicenseController.ActivationRequest("not-a-key")))
        .satisfies(response -> assertThat(response.getStatusCode().value()).isEqualTo(422));
  }

  @Test
  void perpetualStandardLicenceIsUnaffectedByTheGate() throws Exception {
    String standard =
        signed(
            "{\"lic\":\"CSX-PAID\",\"email\":\"buyer@example.com\",\"name\":\"Buyer\",\"issued\":\""
                + LocalDate.of(2026, 8, 17)
                + "\",\"edition\":\"standard\",\"seats\":1,\"ver\":1}");

    for (boolean bypassAllowed : new boolean[] {true, false}) {
      LicenseStatusResponse body = report(publicKey, standard, true, bypassAllowed);
      assertThat(body.licensed()).isTrue();
      assertThat(body.edition()).isEqualTo("standard");
      assertThat(body.enforce()).isTrue();
      assertThat(body.bypass()).isFalse();
    }
  }
}
