package io.cassyx.license.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.cassyx.license.api.License;
import io.cassyx.license.api.LicenseFactory;
import io.cassyx.license.api.LicenseStatus;
import io.cassyx.license.api.LicenseVerifier;
import io.cassyx.license.api.PaymentProvider;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Plan section 9.1/9.2: sign, verify, tamper-detect, and the bypass flag. */
class Ed25519LicenseVerifierTest {

  private static final String PAYLOAD =
      "{\"lic\":\"CSX-AAAA-BBBB-CCCC\",\"email\":\"a@example.com\",\"name\":\"Ada\","
          + "\"issued\":\"2026-08-17\",\"edition\":\"standard\",\"seats\":1,\"ver\":1}";

  private static KeyPair keyPair;

  @BeforeAll
  static void generateKeys() throws Exception {
    keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
  }

  private static String publicKeyBase64() {
    return Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
  }

  private static String signedKey(String payload, PrivateKey key) throws Exception {
    Signature signer = Signature.getInstance("Ed25519");
    signer.initSign(key);
    byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
    signer.update(bytes);
    Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
    return encoder.encodeToString(bytes) + "." + encoder.encodeToString(signer.sign());
  }

  @Test
  void verifiesAGenuineLicense() throws Exception {
    LicenseVerifier verifier = LicenseFactory.verifier(publicKeyBase64());

    LicenseStatus status = verifier.verify(signedKey(PAYLOAD, keyPair.getPrivate()));

    assertThat(status.valid()).isTrue();
    License license = status.licenseOpt().orElseThrow();
    assertThat(license.lic()).isEqualTo("CSX-AAAA-BBBB-CCCC");
    assertThat(license.email()).isEqualTo("a@example.com");
    assertThat(license.issued()).isEqualTo(java.time.LocalDate.of(2026, 8, 17));
    assertThat(license.edition()).isEqualTo(License.STANDARD_EDITION);
    assertThat(license.isBypass()).isFalse();
  }

  @Test
  void detectsTamperedPayload() throws Exception {
    LicenseVerifier verifier = LicenseFactory.verifier(publicKeyBase64());
    String genuine = signedKey(PAYLOAD, keyPair.getPrivate());
    String tamperedPayload =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                PAYLOAD.replace("\"seats\":1", "\"seats\":999").getBytes(StandardCharsets.UTF_8));
    String tampered = tamperedPayload + "." + genuine.split("\\.")[1];

    LicenseStatus status = verifier.verify(tampered);

    assertThat(status.valid()).isFalse();
    assertThat(status.reason()).contains("signature");
  }

  @Test
  void rejectsALicenseSignedByAnotherKey() throws Exception {
    KeyPair attacker = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    LicenseVerifier verifier = LicenseFactory.verifier(publicKeyBase64());

    assertThat(verifier.verify(signedKey(PAYLOAD, attacker.getPrivate())).valid()).isFalse();
  }

  @Test
  void rejectsMalformedInput() {
    LicenseVerifier verifier = LicenseFactory.verifier(publicKeyBase64());

    assertThat(verifier.verify(null).valid()).isFalse();
    assertThat(verifier.verify("  ").valid()).isFalse();
    assertThat(verifier.verify("no-dot").valid()).isFalse();
    assertThat(verifier.verify("!!!.???").valid()).isFalse();
    assertThatThrownBy(() -> LicenseFactory.verifier("not-a-key"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsAValidSignatureOverAnUnreadablePayload() throws Exception {
    LicenseVerifier verifier = LicenseFactory.verifier(publicKeyBase64());

    LicenseStatus status = verifier.verify(signedKey("not json at all", keyPair.getPrivate()));

    assertThat(status.valid()).isFalse();
  }

  @Test
  void bypassFlagShortCircuitsToASyntheticLicense() {
    LicenseVerifier verifier = LicenseFactory.verifier(publicKeyBase64());

    LicenseStatus bypassed = LicenseFactory.check(verifier, null, false);

    assertThat(bypassed.valid()).isTrue();
    assertThat(bypassed.licenseOpt().orElseThrow().edition())
        .isEqualTo(License.BYPASS_EDITION);
    assertThat(bypassed.licenseOpt().orElseThrow().isBypass()).isTrue();
    // and with enforcement ON, a missing key is invalid
    assertThat(LicenseFactory.check(verifier, null, true).valid()).isFalse();
  }

  @Test
  void noopPaymentProviderIsDiscoverableAndRefusesToTrustWebhooks() {
    PaymentProvider provider = LicenseFactory.paymentProvider("noop");

    assertThat(provider).isInstanceOf(NoopPaymentProvider.class);
    assertThat(LicenseFactory.paymentProviders()).extracting(PaymentProvider::id).contains("noop");
    assertThat(provider.verifyWebhook("{}", Map.of())).isEmpty();
    assertThat(
            provider.parseFulfillment(new PaymentProvider.WebhookEvent("evt_1", "x", Map.of())))
        .isEmpty();
    assertThat(
            provider
                .createCheckout(
                    new PaymentProvider.CheckoutRequest(
                        "price_PLACEHOLDER", "a@example.com", "s", "c", "cassyx-abcdefgh",
                        Map.of()))
                .provider())
        .isEqualTo("noop");
    assertThatThrownBy(() -> LicenseFactory.paymentProvider("stripe"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
