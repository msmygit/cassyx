package io.cassyx.core.impl;

/**
 * Lenient dotted-version parsing for the strings clusters actually report.
 *
 * <p>Real values seen in the wild: {@code 5.0.2}, {@code 4.1.3}, {@code 3.11.14},
 * {@code 4.0.0.6816} (Astra), {@code 6.8.35} (DSE), {@code 3.0.8} (ScyllaDB's Cassandra-compatible
 * version), and {@code 5.0.2-SNAPSHOT}. Everything after the first non-numeric character in a
 * segment is ignored, and an unparseable version compares as older than everything - so a version
 * we cannot read never accidentally unlocks a feature.
 */
final class Versions {

  private Versions() {}

  /** The major version, or {@code 0} if it cannot be read. */
  static int major(String version) {
    return segment(version, 0);
  }

  /** The minor version, or {@code 0} if it cannot be read. */
  static int minor(String version) {
    return segment(version, 1);
  }

  /** {@code true} when {@code version} is at least {@code major.minor}. */
  static boolean atLeast(String version, int major, int minor) {
    int actualMajor = major(version);
    if (actualMajor != major) {
      return actualMajor > major;
    }
    return minor(version) >= minor;
  }

  private static int segment(String version, int index) {
    if (version == null || version.isBlank()) {
      return 0;
    }
    String[] parts = version.trim().split("\\.");
    if (index >= parts.length) {
      return 0;
    }
    StringBuilder digits = new StringBuilder();
    for (char c : parts[index].toCharArray()) {
      if (Character.isDigit(c)) {
        digits.append(c);
      } else {
        break;
      }
    }
    if (digits.isEmpty()) {
      return 0;
    }
    try {
      return Integer.parseInt(digits.toString());
    } catch (NumberFormatException e) {
      return 0;
    }
  }
}
