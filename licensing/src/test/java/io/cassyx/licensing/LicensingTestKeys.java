package io.cassyx.licensing;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * One throwaway Ed25519 pair per test JVM, injected as properties before the context starts. Tests
 * must never depend on an operator's real key, and hard-coding one in the repository would mean
 * committing a key capable of minting licences.
 */
public final class LicensingTestKeys {

  private static final KeyPair PAIR = generate();

  private LicensingTestKeys() {}

  private static KeyPair generate() {
    try {
      return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public static String publicKey() {
    return Base64.getEncoder().encodeToString(PAIR.getPublic().getEncoded());
  }

  public static String privateKey() {
    return Base64.getEncoder().encodeToString(PAIR.getPrivate().getEncoded());
  }

  /** Wires the pair plus a webhook secret into a Spring test context. */
  public static void register(DynamicPropertyRegistry registry) {
    registry.add("cassyx.licensing.private-key", LicensingTestKeys::privateKey);
    registry.add("cassyx.licensing.public-key", LicensingTestKeys::publicKey);
    registry.add("cassyx.licensing.token", () -> "test-token");
    registry.add("cassyx.licensing.stripe.webhook-secret", () -> WEBHOOK_SECRET);
  }

  public static final String WEBHOOK_SECRET = "whsec_test_secret_for_unit_tests_only";
}
