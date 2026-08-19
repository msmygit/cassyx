package io.cassyx.api.license;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;

/**
 * Mints real Ed25519 keys for the licence-gate tests.
 *
 * <p>Deliberately signs for real rather than stubbing {@code LicenseVerifier}: the thing under test
 * is whether an unlicensed request is refused, and a stub that answers "valid" proves nothing about
 * a key an actual customer would paste in.
 */
public final class TestLicenses {

  private static final KeyPair KEY_PAIR = generate();

  private TestLicenses() {}

  private static KeyPair generate() {
    try {
      return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** Base64 X.509 SubjectPublicKeyInfo, i.e. what {@code CASSYX_LICENSE_PUBLIC_KEY} carries. */
  public static String publicKey() {
    return Base64.getEncoder().encodeToString(KEY_PAIR.getPublic().getEncoded());
  }

  /** A public key from a DIFFERENT pair, so signatures made above do not verify against it. */
  public static String foreignPublicKey() {
    return Base64.getEncoder().encodeToString(generate().getPublic().getEncoded());
  }

  public static String sign(String payloadJson) {
    try {
      Signature signer = Signature.getInstance("Ed25519");
      signer.initSign(KEY_PAIR.getPrivate());
      byte[] bytes = payloadJson.getBytes(StandardCharsets.UTF_8);
      signer.update(bytes);
      Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
      return encoder.encodeToString(bytes) + "." + encoder.encodeToString(signer.sign());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public static String standard() {
    return sign(payload("standard", 1, ""));
  }

  /** Site licence (plan section 9.2): unlimited seats, granted, works in a release build. */
  public static String site() {
    return sign(payload("site", 0, ""));
  }

  /** Live trial (plan section 9.4): expires well after any plausible test run. */
  public static String trial() {
    return sign(payload("trial", 1, ",\"expires\":\"2999-01-01\""));
  }

  public static String expiredTrial() {
    return sign(payload("trial", 1, ",\"expires\":\"2020-01-01\""));
  }

  /** Genuine key sold for major version 1 (plan section 9.5). */
  public static String scopedToMajorOne() {
    return sign(payload("standard", 1, ",\"scope\":1"));
  }

  /** Correctly shaped, but the signature is not ours. */
  public static String tampered() {
    String payload = payload("standard", 1, "");
    return Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(payload.getBytes(StandardCharsets.UTF_8))
        + ".AAAA";
  }

  private static String payload(String edition, int seats, String extras) {
    return "{\"lic\":\"CSX-TEST-0001\",\"email\":\"buyer@example.com\",\"name\":\"Buyer\","
        + "\"issued\":\"2026-08-17\",\"edition\":\""
        + edition
        + "\",\"seats\":"
        + seats
        + ",\"ver\":1"
        + extras
        + "}";
  }
}
