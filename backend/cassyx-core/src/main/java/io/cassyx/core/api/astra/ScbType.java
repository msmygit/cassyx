package io.cassyx.core.api.astra;

/**
 * Secure connect bundle type. EXACTLY TWO VALUES - see plan section 3.1, deviation 1.
 *
 * <p>DataStax's reference {@code AstraDevOpsClient} documents three types ({@code default},
 * {@code region}, {@code custom}) but its switch only implements two, so {@code region} falls
 * through and logs "Unknown SCB type". Regional selection is in fact an orthogonal input - the
 * {@code region} field of {@link ScbSelector} - so a third enum constant would be a bug, not a
 * feature. Do not add one.
 */
public enum ScbType {
  DEFAULT,
  CUSTOM;

  /**
   * Parses a wire value. Null or blank maps to {@link #DEFAULT} - the reference implementation
   * calls {@code scbType.toLowerCase()} unguarded and NPEs here (plan section 3.1, deviation 2).
   *
   * @throws IllegalArgumentException on any other value, including the phantom "region"
   */
  public static ScbType parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return DEFAULT;
    }
    String normalized = raw.trim().toLowerCase(java.util.Locale.ROOT);
    return switch (normalized) {
      case "default" -> DEFAULT;
      case "custom" -> CUSTOM;
      case "region" ->
          throw new IllegalArgumentException(
              "'region' is not a secure connect bundle type; select a region with the separate "
                  + "region field and use scbType 'default' or 'custom'");
      default ->
          throw new IllegalArgumentException(
              "Unsupported secure connect bundle type '" + raw + "'; expected 'default' or 'custom'");
    };
  }
}
