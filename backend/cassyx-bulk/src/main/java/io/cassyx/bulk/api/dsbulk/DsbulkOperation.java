package io.cassyx.bulk.api.dsbulk;

import java.util.Locale;

/**
 * The three DSBulk workflows cassyx drives (plan sections 5.3 / 5.4).
 *
 * <p>Workflows are discovered by DSBulk through {@link java.util.ServiceLoader}, one module per
 * workflow ({@code dsbulk-workflow-load}, {@code -unload}, {@code -count}). {@link
 * DsbulkDistribution#verify()} asserts all three are present in the shipped distribution, because a
 * missing workflow jar fails at run time with an unhelpful "unknown command".
 */
public enum DsbulkOperation {
  UNLOAD,
  LOAD,
  COUNT;

  /** The first positional argument of the {@code dsbulk} command line. */
  public String command() {
    return name().toLowerCase(Locale.ROOT);
  }

  /** {@code true} when the workflow reads from the cluster rather than writing to it. */
  public boolean isRead() {
    return this != LOAD;
  }

  public static DsbulkOperation parse(String value) {
    if (value == null || value.isBlank()) {
      throw new DsbulkException("Missing DSBulk operation; expected one of UNLOAD, LOAD, COUNT");
    }
    try {
      return valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new DsbulkException("Unknown DSBulk operation '" + value + "'", e);
    }
  }
}
