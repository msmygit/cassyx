package io.cassyx.core.api;

import java.nio.charset.StandardCharsets;

/**
 * Envelope encryption for everything cassyx stores that must not be readable from the H2 file:
 * passwords, Astra tokens, SSH private keys, keystore passwords and secure connect bundles
 * (plan section 3).
 *
 * <p>AES-256-GCM with a fresh 96-bit nonce per value. GCM is authenticated, so a tampered
 * ciphertext fails to decrypt rather than silently yielding garbage that then gets sent to a
 * cluster as a password.
 *
 * <p>Implementations take the key from {@code CASSYX_SECRET_KEY}. There is deliberately no
 * "no key configured" fallback that stores plaintext: a deployment that has not set a key must
 * fail loudly when it first tries to save a credential, not quietly write secrets in the clear.
 */
public interface SecretCipher {

  /** Environment variable holding the base64 (or hex) encoded 256-bit key. */
  String KEY_ENV_VAR = "CASSYX_SECRET_KEY";

  /** Required key length in bytes. AES-256. */
  int KEY_LENGTH_BYTES = 32;

  /** GCM nonce length in bytes; 96 bits is the only size GCM is specified to be safe at. */
  int NONCE_LENGTH_BYTES = 12;

  /** @throws SecretCipherException if encryption fails */
  EncryptedValue encrypt(byte[] plaintext);

  /** @throws SecretCipherException if the ciphertext was tampered with or the key is wrong */
  byte[] decrypt(EncryptedValue value);

  default EncryptedValue encryptString(String plaintext) {
    return encrypt(plaintext.getBytes(StandardCharsets.UTF_8));
  }

  /** Decrypts to a {@link Secret}, so the plaintext cannot leak through a {@code toString()}. */
  default Secret decryptSecret(EncryptedValue value) {
    return value == null ? Secret.empty() : Secret.of(new String(decrypt(value), StandardCharsets.UTF_8));
  }

  /** Convenience for the common "encrypt a credential, or store nothing" case. */
  default EncryptedValue encryptSecret(Secret secret) {
    return secret == null || secret.isEmpty() ? null : encryptString(secret.reveal());
  }
}
