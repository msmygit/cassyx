package io.cassyx.api.connections;

import io.cassyx.core.api.EncryptedValue;
import io.cassyx.core.api.SecretCipher;
import io.cassyx.core.api.SecretCipherException;

/**
 * The cipher used when {@code CASSYX_SECRET_KEY} is not set: it refuses every operation.
 *
 * <p>Two bad options were rejected to get here.
 *
 * <ul>
 *   <li><b>Generate a key at startup.</b> Every restart would then invalidate every stored
 *       credential, and the failure would look like "Cassandra suddenly rejects my password".
 *   <li><b>Fail the whole application context.</b> That takes down {@code /api/health}, the license
 *       screen and every other feature over a setting that only affects saving a connection - and,
 *       in a product that is deployed by its users, it replaces an actionable UI message with a
 *       container that will not start.
 * </ul>
 *
 * <p>So the app boots, everything that does not touch a credential works, and the first attempt to
 * save or read one fails with the command that fixes it. What is explicitly NOT on the list of
 * options is storing anything in plaintext.
 */
final class UnconfiguredSecretCipher implements SecretCipher {

  static final String MESSAGE =
      "CASSYX_SECRET_KEY is not set, so cassyx cannot store or read connection credentials. "
          + "It encrypts cluster passwords, Astra tokens and secure connect bundles at rest with "
          + "AES-256-GCM and will not fall back to plaintext. Generate a key with "
          + "`openssl rand -base64 32` and set CASSYX_SECRET_KEY, then restart.";

  @Override
  public EncryptedValue encrypt(byte[] plaintext) {
    throw new SecretCipherException(MESSAGE);
  }

  @Override
  public byte[] decrypt(EncryptedValue value) {
    throw new SecretCipherException(MESSAGE);
  }
}
