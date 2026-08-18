package io.cassyx.core.api;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

/**
 * Ciphertext plus the nonce it was produced with (plan section 3, and the {@code *_cipher} /
 * {@code *_iv} column pairs in {@code V1__baseline.sql}).
 *
 * <p>The nonce is stored per value and never derived, because AES-GCM catastrophically loses
 * confidentiality <b>and</b> authenticity if a nonce is ever reused under the same key - a single
 * repeat leaks the XOR of two plaintexts and the authentication subkey. Deriving a nonce from the
 * row id, the column name or a counter is the standard way that happens, so the type makes the
 * nonce a first-class part of the stored value.
 *
 * <p>Serialised as {@code null} by Jackson for the same reason as {@link Secret}: an
 * {@code EncryptedValue} that reaches a response body is still a secret at rest, and the contract
 * says response schemas carry presence flags only.
 */
@JsonSerialize(using = EncryptedValue.RedactingSerializer.class)
public record EncryptedValue(byte[] nonce, byte[] ciphertext) {

  public EncryptedValue {
    Objects.requireNonNull(nonce, "nonce");
    Objects.requireNonNull(ciphertext, "ciphertext");
    nonce = nonce.clone();
    ciphertext = ciphertext.clone();
  }

  @Override
  public byte[] nonce() {
    return nonce.clone();
  }

  @Override
  public byte[] ciphertext() {
    return ciphertext.clone();
  }

  public int length() {
    return ciphertext.length;
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof EncryptedValue other
        && Arrays.equals(nonce, other.nonce)
        && Arrays.equals(ciphertext, other.ciphertext);
  }

  @Override
  public int hashCode() {
    return 31 * Arrays.hashCode(nonce) + Arrays.hashCode(ciphertext);
  }

  /** Never renders the bytes: ciphertext in a log line is an oracle for an attacker with the key. */
  @Override
  public String toString() {
    return "EncryptedValue[" + ciphertext.length + " bytes]";
  }

  /** Emits {@code null}, whatever DTO an {@code EncryptedValue} is accidentally embedded in. */
  public static final class RedactingSerializer extends StdSerializer<EncryptedValue> {

    private static final long serialVersionUID = 1L;

    public RedactingSerializer() {
      super(EncryptedValue.class);
    }

    @Override
    public void serialize(EncryptedValue value, JsonGenerator gen, SerializerProvider provider)
        throws IOException {
      gen.writeNull();
    }
  }
}
