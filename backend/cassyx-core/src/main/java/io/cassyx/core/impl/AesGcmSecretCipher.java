package io.cassyx.core.impl;

import io.cassyx.core.api.EncryptedValue;
import io.cassyx.core.api.SecretCipher;
import io.cassyx.core.api.SecretCipherException;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM with a fresh 96-bit nonce per value (plan section 3).
 *
 * <p>Notes that matter and are easy to get wrong:
 *
 * <ul>
 *   <li><b>A new nonce per {@code encrypt} call, from {@link SecureRandom}.</b> Never derived, never
 *       a counter shared across restarts. Nonce reuse under one key is the single catastrophic
 *       failure of GCM.
 *   <li><b>128-bit tag.</b> Truncating it to save 8 bytes in a database column weakens forgery
 *       resistance for no meaningful gain.
 *   <li><b>The key never appears in a message.</b> Neither does the plaintext, nor the reason a
 *       decryption failed.
 *   <li>The {@link Cipher} instance is created per call. It is not thread-safe and this class is
 *       shared by every request thread; caching one in a field is a data race that silently
 *       produces corrupt ciphertext under load.
 * </ul>
 */
public final class AesGcmSecretCipher implements SecretCipher {

  private static final String TRANSFORMATION = "AES/GCM/NoPadding";
  private static final String ALGORITHM = "AES";
  private static final int TAG_LENGTH_BITS = 128;

  private final SecretKeySpec key;
  private final SecureRandom random;

  /**
   * @param keyBytes exactly 32 bytes
   * @throws SecretCipherException if the key is the wrong length
   */
  public AesGcmSecretCipher(byte[] keyBytes) {
    this(keyBytes, new SecureRandom());
  }

  AesGcmSecretCipher(byte[] keyBytes, SecureRandom random) {
    if (keyBytes == null || keyBytes.length != KEY_LENGTH_BYTES) {
      throw new SecretCipherException(
          "CASSYX_SECRET_KEY must decode to exactly "
              + KEY_LENGTH_BYTES
              + " bytes (AES-256); got "
              + (keyBytes == null ? 0 : keyBytes.length));
    }
    this.key = new SecretKeySpec(keyBytes, ALGORITHM);
    this.random = random;
  }

  /**
   * Reads {@code CASSYX_SECRET_KEY} from the environment.
   *
   * @throws SecretCipherException when it is unset. Deliberately fatal rather than falling back to
   *     a generated key: a generated key means every restart silently invalidates every stored
   *     credential, and falling back to plaintext means writing secrets in the clear.
   */
  public static AesGcmSecretCipher fromEnvironment() {
    return fromKeyString(System.getenv(KEY_ENV_VAR));
  }

  /** Accepts base64 (standard or URL-safe), hex, or a raw 32-character key. */
  public static AesGcmSecretCipher fromKeyString(String configured) {
    if (configured == null || configured.isBlank()) {
      throw new SecretCipherException(
          KEY_ENV_VAR
              + " is not set. cassyx stores cluster passwords, Astra tokens and secure connect "
              + "bundles encrypted at rest and will not fall back to plaintext. Generate one with: "
              + "openssl rand -base64 32");
    }
    return new AesGcmSecretCipher(decodeKey(configured.trim()));
  }

  /** Visible for testing and for the one-off key generation helper. */
  public static byte[] decodeKey(String configured) {
    String value = configured.trim();
    if (value.length() == KEY_LENGTH_BYTES * 2 && value.matches("(?i)[0-9a-f]+")) {
      return HexFormat.of().parseHex(value.toLowerCase(Locale.ROOT));
    }
    try {
      return Base64.getDecoder().decode(value);
    } catch (IllegalArgumentException ignored) {
      // fall through to the URL-safe alphabet before giving up
    }
    try {
      return Base64.getUrlDecoder().decode(value);
    } catch (IllegalArgumentException e) {
      throw new SecretCipherException(
          KEY_ENV_VAR + " is not valid base64 or hex. Generate one with: openssl rand -base64 32");
    }
  }

  @Override
  public EncryptedValue encrypt(byte[] plaintext) {
    if (plaintext == null) {
      throw new SecretCipherException("Nothing to encrypt");
    }
    byte[] nonce = new byte[NONCE_LENGTH_BYTES];
    random.nextBytes(nonce);
    try {
      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
      return new EncryptedValue(nonce, cipher.doFinal(plaintext));
    } catch (GeneralSecurityException e) {
      // No plaintext, no key, no nonce in the message.
      throw new SecretCipherException("Could not encrypt a stored credential", e);
    }
  }

  @Override
  public byte[] decrypt(EncryptedValue value) {
    if (value == null) {
      throw new SecretCipherException("Nothing to decrypt");
    }
    byte[] nonce = value.nonce();
    if (nonce.length != NONCE_LENGTH_BYTES) {
      throw new SecretCipherException("Stored credential has a malformed nonce");
    }
    try {
      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
      return cipher.doFinal(value.ciphertext());
    } catch (GeneralSecurityException e) {
      // Deliberately does NOT distinguish "wrong key" from "tampered ciphertext": telling an
      // attacker which one failed is exactly the oracle authenticated encryption exists to deny.
      // The cause is retained for the server log, never for the response body.
      throw new SecretCipherException(
          "A stored credential could not be decrypted. This normally means CASSYX_SECRET_KEY "
              + "changed since the connection was saved; re-enter the credential to fix it.",
          e);
    }
  }

  /** Convenience for operators: a fresh base64 key of the right length. */
  public static String generateKey() {
    byte[] bytes = new byte[KEY_LENGTH_BYTES];
    new SecureRandom().nextBytes(bytes);
    return Base64.getEncoder().encodeToString(bytes);
  }
}
