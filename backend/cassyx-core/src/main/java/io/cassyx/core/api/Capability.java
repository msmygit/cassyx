package io.cassyx.core.api;

import java.util.Locale;

/**
 * Feature gates driven by the capability matrix in plan section 7.1.
 *
 * <p>{@link #wireName()} is the identifier the API contract uses ({@code CapabilityName} in
 * {@code openapi/cassyx-api.yaml}); the constant names stay Java-idiomatic. Keeping the mapping
 * here rather than in the web layer means a new capability cannot be added without deciding what
 * it is called on the wire.
 */
public enum Capability {
  SAI("sai"),
  /** {@code vector<float, N>} columns plus {@code ORDER BY ... ANN OF}. */
  VECTOR_ANN("vector"),
  MATERIALIZED_VIEWS("materializedViews"),
  UDF_UDA("udfUda"),
  TRUNCATE("truncate"),
  /**
   * {@code WHERE token(pk) > ? AND token(pk) <= ?}. Amazon Keyspaces does not implement it, so the
   * bulk path must fall back to plain driver paging there (plan section 7.1).
   */
  TOKEN_RANGE_SCAN("tokenRangeScan"),
  DSE_SEARCH("dseSearch"),
  ROLES_PERMISSIONS("rolesPermissions"),
  TRACING("tracing"),
  /** Lightweight transactions: {@code IF NOT EXISTS} / {@code IF <cond>}. */
  LWT("lwt"),
  COUNTERS("counters"),
  VIRTUAL_TABLES("virtualTables"),
  DESCRIBE_STATEMENT("describeStatement");

  private final String wireName;

  Capability(String wireName) {
    this.wireName = wireName;
  }

  /** The name used in the API contract. */
  public String wireName() {
    return wireName;
  }

  /**
   * Inverse of {@link #wireName()}.
   *
   * @throws IllegalArgumentException if {@code wireName} names no capability
   */
  public static Capability fromWireName(String wireName) {
    if (wireName != null) {
      String normalized = wireName.trim();
      for (Capability capability : values()) {
        if (capability.wireName.equalsIgnoreCase(normalized)
            || capability.name().equalsIgnoreCase(normalized.toUpperCase(Locale.ROOT))) {
          return capability;
        }
      }
    }
    throw new IllegalArgumentException("Unknown capability '" + wireName + "'");
  }
}
