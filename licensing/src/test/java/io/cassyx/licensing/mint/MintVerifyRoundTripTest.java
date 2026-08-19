package io.cassyx.licensing.mint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.cassyx.license.api.License;
import io.cassyx.license.api.LicenseState;
import io.cassyx.license.api.LicenseStatus;
import io.cassyx.license.impl.Ed25519LicenseVerifier;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The single most important test in this workstream: a key minted here must verify with the exact
 * {@code Ed25519LicenseVerifier} the shipped product runs (plan section 9.1). If this ever goes
 * red, every licence sold since it broke is worthless to the customer holding it.
 */
class MintVerifyRoundTripTest {

  private static KeyPair keyPair;

  @BeforeAll
  static void generateKeys() throws Exception {
    keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
  }

  private static String publicKeyBase64() {
    return Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
  }

  private static String privateKeyBase64() {
    return Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());
  }

  private static Ed25519LicenseMinter minterOn(LocalDate today) {
    return new Ed25519LicenseMinter(
        Ed25519LicenseMinter.decodePrivateKey(privateKeyBase64()),
        Clock.fixed(today.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC));
  }

  @Test
  void aMintedPerpetualKeyVerifiesThroughTheShippedVerifier() {
    License license =
        new License(
            "CSX-4H7K-9QP2-M1XR",
            "ops@example.com",
            "Example GmbH",
            LocalDate.of(2026, 8, 17),
            License.STANDARD_EDITION,
            1,
            1);

    String key = minterOn(LocalDate.of(2026, 8, 17)).mint(license);
    LicenseStatus status = new Ed25519LicenseVerifier(publicKeyBase64()).verify(key);

    assertThat(status.valid()).isTrue();
    License verified = status.licenseOpt().orElseThrow();
    assertThat(verified.lic()).isEqualTo("CSX-4H7K-9QP2-M1XR");
    assertThat(verified.email()).isEqualTo("ops@example.com");
    assertThat(verified.name()).isEqualTo("Example GmbH");
    assertThat(verified.issued()).isEqualTo(LocalDate.of(2026, 8, 17));
    assertThat(verified.edition()).isEqualTo(License.STANDARD_EDITION);
    assertThat(verified.seats()).isEqualTo(1);
    assertThat(verified.ver()).isEqualTo(1);
    // Absent means perpetual and unrestricted; the minter must OMIT them, not emit nulls.
    assertThat(verified.expires()).isNull();
    assertThat(verified.scope()).isNull();
    assertThat(status.daysRemaining()).isNull();
  }

  @Test
  void omitsOptionalFieldsEntirelyRatherThanEmittingNulls() {
    String json =
        Ed25519LicenseMinter.payloadJson(
            new License("CSX-A", "a@b.c", "A", LocalDate.of(2026, 1, 1), "standard", 1, 1));

    assertThat(json).doesNotContain("expires").doesNotContain("scope");
    // Field order and spelling are the wire format; a rename here silently invalidates every key.
    assertThat(json)
        .isEqualTo(
            "{\"lic\":\"CSX-A\",\"email\":\"a@b.c\",\"name\":\"A\",\"issued\":\"2026-01-01\","
                + "\"edition\":\"standard\",\"seats\":1,\"ver\":1}");
  }

  @Test
  void aMintedTrialExpiresInclusivelyOnItsLastDay() {
    LocalDate issued = LocalDate.of(2026, 8, 17);
    // 14 days starting on the 17th: the 30th is the last valid day, the 31st is not.
    License trial =
        new License(
            "CSX-TRIAL",
            "ops@example.com",
            "Example GmbH",
            issued,
            License.TRIAL_EDITION,
            1,
            1,
            issued.plusDays(License.DEFAULT_TRIAL_DAYS - 1L));
    String key = minterOn(issued).mint(trial);

    assertThat(verifyOn(key, LocalDate.of(2026, 8, 30)).valid()).isTrue();
    assertThat(verifyOn(key, LocalDate.of(2026, 8, 30)).daysRemaining()).isZero();
    assertThat(verifyOn(key, LocalDate.of(2026, 8, 17)).daysRemaining()).isEqualTo(13);

    LicenseStatus lapsed = verifyOn(key, LocalDate.of(2026, 8, 31));
    assertThat(lapsed.valid()).isFalse();
    assertThat(lapsed.state()).isEqualTo(LicenseState.EXPIRED);
    // The payload is retained so checkout can be pre-filled with the evaluator's details.
    assertThat(lapsed.licenseOpt().orElseThrow().email()).isEqualTo("ops@example.com");
  }

  @Test
  void aScopedKeyCoversItsMajorAndEveryEarlierOne() {
    License scoped =
        new License(
            "CSX-SCOPED", "a@b.c", "A", LocalDate.of(2026, 8, 17), "standard", 1, 1, null, 2);
    String key = minterOn(LocalDate.of(2026, 8, 17)).mint(scoped);

    assertThat(scopedVerifier(1).verify(key).valid()).isTrue();
    assertThat(scopedVerifier(2).verify(key).valid()).isTrue();
    LicenseStatus tooNew = scopedVerifier(3).verify(key);
    assertThat(tooNew.state()).isEqualTo(LicenseState.UPGRADE_REQUIRED);
  }

  @Test
  void aKeyMintedWithADifferentPrivateKeyDoesNotVerify() throws Exception {
    KeyPair other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    String key =
        new Ed25519LicenseMinter(other.getPrivate(), Clock.systemUTC())
            .mint(new License("CSX-X", "a@b.c", "A", LocalDate.of(2026, 8, 17), "standard", 1, 1));

    LicenseStatus status = new Ed25519LicenseVerifier(publicKeyBase64()).verify(key);

    assertThat(status.valid()).isFalse();
    assertThat(status.state()).isEqualTo(LicenseState.INVALID_SIGNATURE);
  }

  @Test
  void aTamperedPayloadDoesNotVerify() {
    String key =
        minterOn(LocalDate.of(2026, 8, 17))
            .mint(
                new License(
                    "CSX-T", "a@b.c", "A", LocalDate.of(2026, 8, 17), License.TRIAL_EDITION, 1, 1,
                    LocalDate.of(2026, 8, 30)));
    String payload = key.split("\\.")[0];
    String edited =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                new String(Base64.getUrlDecoder().decode(payload))
                    .replace("2026-08-30", "2036-08-30")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));

    // Extending your own trial by editing the date: exactly what the signature exists to stop.
    assertThat(new Ed25519LicenseVerifier(publicKeyBase64()).verify(edited + "." + key.split("\\.")[1])
            .valid())
        .isFalse();
  }

  @Test
  void generatedLicenceCodesMatchTheContractPattern() {
    for (int i = 0; i < 50; i++) {
      assertThat(Ed25519LicenseMinter.newLicenseCode()).matches("^CSX(-[A-Z0-9]{4}){3}$");
    }
  }

  @Test
  void rejectsAnUnusablePrivateKeyLoudly() {
    assertThatThrownBy(() -> new Ed25519LicenseMinter("not-a-key"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private LicenseStatus verifyOn(String key, LocalDate today) {
    return new Ed25519LicenseVerifier(
            Ed25519LicenseVerifier.decodePublicKey(publicKeyBase64()), clockAt(today))
        .verify(key);
  }

  private Ed25519LicenseVerifier scopedVerifier(int appMajor) {
    return new Ed25519LicenseVerifier(
        Ed25519LicenseVerifier.decodePublicKey(publicKeyBase64()),
        clockAt(LocalDate.of(2026, 8, 17)),
        appMajor);
  }

  private static Clock clockAt(LocalDate day) {
    Instant instant = day.atStartOfDay(ZoneOffset.UTC).toInstant();
    return Clock.fixed(instant, ZoneOffset.UTC);
  }
}
