package io.cassyx.core.api;

/**
 * Encryption or decryption failed.
 *
 * <p>Messages here never carry the plaintext, the key, or the ciphertext. A decryption failure is
 * reported as exactly that - "the stored credential could not be decrypted" - because saying
 * <i>why</i> (bad tag versus bad key versus wrong length) is a padding-oracle-shaped gift.
 */
public class SecretCipherException extends CassyxCoreException {

  private static final long serialVersionUID = 1L;

  public SecretCipherException(String message) {
    super(message);
  }

  public SecretCipherException(String message, Throwable cause) {
    super(message, cause);
  }
}
