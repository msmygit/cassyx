package io.cassyx.core.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.cassyx.core.api.EncryptedValue;
import io.cassyx.core.api.Secret;
import io.cassyx.core.api.SecretCipher;
import io.cassyx.core.api.SecretCipherException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AesGcmSecretCipherTest {

  private static final String KEY_BASE64 =
      Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

  private final SecretCipher cipher = AesGcmSecretCipher.fromKeyString(KEY_BASE64);

  @Test
  void roundTripsACredential() {
    EncryptedValue encrypted = cipher.encryptString("s3cr3t-cassandra-password");

    assertThat(cipher.decryptSecret(encrypted).reveal()).isEqualTo("s3cr3t-cassandra-password");
  }

  @Test
  void roundTripsBinaryMaterialLikeASecureConnectBundle() {
    byte[] bundle = new byte[8192];
    new SecureRandom().nextBytes(bundle);

    assertThat(cipher.decrypt(cipher.encrypt(bundle))).isEqualTo(bundle);
  }

  @Test
  @DisplayName("a fresh nonce per value - the one mistake AES-GCM does not survive")
  void neverReusesANonce() {
    Set<String> nonces = new HashSet<>();
    Set<String> ciphertexts = new HashSet<>();
    for (int i = 0; i < 500; i++) {
      EncryptedValue value = cipher.encryptString("the same plaintext every time");
      nonces.add(Base64.getEncoder().encodeToString(value.nonce()));
      ciphertexts.add(Base64.getEncoder().encodeToString(value.ciphertext()));
    }

    assertThat(nonces).hasSize(500);
    // Same plaintext, different ciphertext: the encryption is not deterministic, so an observer
    // with database access cannot tell which connections share a password.
    assertThat(ciphertexts).hasSize(500);
  }

  @Test
  void producesA96BitNonce() {
    assertThat(cipher.encryptString("x").nonce()).hasSize(SecretCipher.NONCE_LENGTH_BYTES);
  }

  @Test
  @DisplayName("a tampered ciphertext fails to decrypt rather than yielding garbage")
  void detectsTampering() {
    EncryptedValue original = cipher.encryptString("correct horse battery staple");
    byte[] tampered = original.ciphertext();
    tampered[0] ^= 0x01;

    assertThatThrownBy(() -> cipher.decrypt(new EncryptedValue(original.nonce(), tampered)))
        .isInstanceOf(SecretCipherException.class)
        .hasMessageContaining("could not be decrypted");
  }

  @Test
  void rejectsAValueEncryptedUnderADifferentKey() {
    EncryptedValue value = cipher.encryptString("password");
    SecretCipher other = AesGcmSecretCipher.fromKeyString(AesGcmSecretCipher.generateKey());

    assertThatThrownBy(() -> other.decrypt(value))
        .isInstanceOf(SecretCipherException.class)
        .hasMessageContaining("CASSYX_SECRET_KEY");
  }

  @Test
  void rejectsAMalformedNonce() {
    assertThatThrownBy(() -> cipher.decrypt(new EncryptedValue(new byte[4], new byte[32])))
        .isInstanceOf(SecretCipherException.class)
        .hasMessageContaining("malformed nonce");
  }

  @Test
  void rejectsNulls() {
    assertThatThrownBy(() -> cipher.encrypt(null)).isInstanceOf(SecretCipherException.class);
    assertThatThrownBy(() -> cipher.decrypt(null)).isInstanceOf(SecretCipherException.class);
  }

  @Test
  @DisplayName("no key configured is fatal, never a silent plaintext fallback")
  void refusesToRunWithoutAKey() {
    assertThatThrownBy(() -> AesGcmSecretCipher.fromKeyString(null))
        .isInstanceOf(SecretCipherException.class)
        .hasMessageContaining("CASSYX_SECRET_KEY is not set")
        .hasMessageContaining("openssl rand -base64 32");
    assertThatThrownBy(() -> AesGcmSecretCipher.fromKeyString("   "))
        .isInstanceOf(SecretCipherException.class);
  }

  @Test
  void rejectsAKeyOfTheWrongLength() {
    assertThatThrownBy(
            () ->
                AesGcmSecretCipher.fromKeyString(
                    Base64.getEncoder().encodeToString("too short".getBytes(StandardCharsets.UTF_8))))
        .isInstanceOf(SecretCipherException.class)
        .hasMessageContaining("AES-256");
    assertThatThrownBy(() -> new AesGcmSecretCipher(null))
        .isInstanceOf(SecretCipherException.class);
  }

  @Test
  void acceptsBase64UrlSafeAndHexKeys() {
    byte[] raw = new byte[32];
    new SecureRandom().nextBytes(raw);

    assertThat(AesGcmSecretCipher.decodeKey(Base64.getEncoder().encodeToString(raw)))
        .isEqualTo(raw);
    assertThat(AesGcmSecretCipher.decodeKey(Base64.getUrlEncoder().encodeToString(raw)))
        .isEqualTo(raw);
    assertThat(AesGcmSecretCipher.decodeKey(java.util.HexFormat.of().formatHex(raw)))
        .isEqualTo(raw);
  }

  @Test
  void rejectsAKeyThatIsNeitherBase64NorHex() {
    assertThatThrownBy(() -> AesGcmSecretCipher.decodeKey("not a key!!! ***"))
        .isInstanceOf(SecretCipherException.class)
        .hasMessageContaining("base64 or hex");
  }

  @Test
  void generatesA256BitKey() {
    assertThat(Base64.getDecoder().decode(AesGcmSecretCipher.generateKey()))
        .hasSize(SecretCipher.KEY_LENGTH_BYTES);
  }

  @Test
  void encryptSecretSkipsEmptyCredentials() {
    assertThat(cipher.encryptSecret(Secret.empty())).isNull();
    assertThat(cipher.encryptSecret(null)).isNull();
    assertThat(cipher.decryptSecret(null)).isEqualTo(Secret.empty());
    assertThat(cipher.encryptSecret(Secret.of("present"))).isNotNull();
  }

  @Test
  void fromEnvironmentReportsTheVariableNameWhenUnset() {
    // CASSYX_SECRET_KEY is not set in the test JVM, so this exercises the operator-facing message.
    if (System.getenv(SecretCipher.KEY_ENV_VAR) == null) {
      assertThatThrownBy(AesGcmSecretCipher::fromEnvironment)
          .isInstanceOf(SecretCipherException.class)
          .hasMessageContaining(SecretCipher.KEY_ENV_VAR);
    }
  }
}
