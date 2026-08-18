package io.cassyx.api.connections.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Which bundle variant to take from a datacenter entry. EXACTLY TWO VALUES (plan section 3.1,
 * deviation 1).
 *
 * <p>{@code region} is deliberately not one of them: region selection is the separate, orthogonal
 * {@code region} field. The DataStax reference implementation documents a three-valued type whose
 * switch handles only two, so passing {@code region} there falls through and logs "Unknown SCB
 * type". Here it is rejected with a message that says what to do instead.
 */
public enum ScbType {
  DEFAULT("default"),
  CUSTOM("custom");

  private final String wireName;

  ScbType(String wireName) {
    this.wireName = wireName;
  }

  @JsonValue
  public String wireName() {
    return wireName;
  }

  public io.cassyx.core.api.astra.ScbType toCore() {
    return this == CUSTOM
        ? io.cassyx.core.api.astra.ScbType.CUSTOM
        : io.cassyx.core.api.astra.ScbType.DEFAULT;
  }

  public static ScbType fromCore(io.cassyx.core.api.astra.ScbType type) {
    return type == io.cassyx.core.api.astra.ScbType.CUSTOM ? CUSTOM : DEFAULT;
  }

  /**
   * @throws IllegalArgumentException naming the two legal values, and explaining that regional
   *     selection lives in its own field
   */
  @JsonCreator
  public static ScbType parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return DEFAULT;
    }
    return switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
      case "default" -> DEFAULT;
      case "custom" -> CUSTOM;
      case "region" ->
          throw new IllegalArgumentException(
              "scbType must be one of [\"default\", \"custom\"]. Received \"region\" - regional "
                  + "selection is expressed through the separate \"region\" field.");
      default ->
          throw new IllegalArgumentException(
              "scbType must be one of [\"default\", \"custom\"]. Received \"" + raw + "\".");
    };
  }
}
