package io.cassyx.core.api;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

/**
 * A credential that cannot leak by accident (plan sections 2.3 and 3).
 *
 * <p>The contract says secrets are WRITE-ONLY: they appear in request bodies, never in responses,
 * never in logs, never in an error payload. Relying on eight parallel workstreams to remember that
 * is not a control - so this type makes it structural:
 *
 * <ul>
 *   <li>{@link #toString()} returns a redaction marker, so string interpolation into a log line,
 *       an exception message or a job record cannot leak the value
 *   <li>Jackson serialisation emits {@code null} - putting a {@code Secret} in a response DTO
 *       cannot expose it. Expose {@link #isPresent()} as {@code hasPassword} instead.
 *   <li>Reading the value requires the deliberately greppable {@link #reveal()} call
 * </ul>
 */
@JsonSerialize(using = Secret.RedactingSerializer.class)
public final class Secret {

  /** What a redacted secret renders as. */
  public static final String REDACTED = "<redacted>";

  private static final Secret EMPTY = new Secret(null);

  private final char[] value;

  private Secret(char[] value) {
    this.value = value;
  }

  public static Secret empty() {
    return EMPTY;
  }

  /** @return {@link #empty()} when {@code value} is null or blank */
  public static Secret of(String value) {
    return value == null || value.isEmpty() ? EMPTY : new Secret(value.toCharArray());
  }

  public static Secret of(char[] value) {
    return value == null || value.length == 0 ? EMPTY : new Secret(Arrays.copyOf(value, value.length));
  }

  public boolean isPresent() {
    return value != null && value.length > 0;
  }

  public boolean isEmpty() {
    return !isPresent();
  }

  /** Deliberately explicit and greppable: every call site is an auditable use of a credential. */
  public String reveal() {
    return value == null ? null : new String(value);
  }

  /** Copy of the raw characters, for APIs that avoid interning credentials as Strings. */
  public char[] revealChars() {
    return value == null ? new char[0] : Arrays.copyOf(value, value.length);
  }

  @Override
  public String toString() {
    return isPresent() ? REDACTED : "<none>";
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof Secret other)) {
      return false;
    }
    return Arrays.equals(value, other.value);
  }

  @Override
  public int hashCode() {
    return Objects.hash(Arrays.hashCode(value));
  }

  /** Serialises every {@link Secret} as {@code null}, whatever DTO it is embedded in. */
  public static final class RedactingSerializer extends StdSerializer<Secret> {

    private static final long serialVersionUID = 1L;

    public RedactingSerializer() {
      super(Secret.class);
    }

    @Override
    public void serialize(Secret value, JsonGenerator gen, SerializerProvider provider)
        throws IOException {
      gen.writeNull();
    }
  }
}
