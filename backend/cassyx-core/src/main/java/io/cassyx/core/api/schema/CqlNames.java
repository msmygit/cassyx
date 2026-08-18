package io.cassyx.core.api.schema;

import com.datastax.oss.driver.api.core.CqlIdentifier;

/**
 * Identifier and literal rendering for generated CQL.
 *
 * <p>Quoting is delegated to the driver's {@link CqlIdentifier}, which quotes exactly when CQL
 * requires it - a hand-rolled reserved-word list drifts with every Cassandra release, and a missed
 * quote silently targets a different object.
 *
 * <p>Declared as an interface with static members on purpose: everything in an {@code ...api}
 * package must be an interface, enum or record (plan section 2.1, ArchUnit-enforced).
 */
public interface CqlNames {

  /** Quotes {@code name} only where CQL requires it. */
  static String quote(String name) {
    if (name == null || name.isEmpty()) {
      throw new IllegalArgumentException("CQL identifier must not be empty");
    }
    return CqlIdentifier.fromInternal(name).asCql(true);
  }

  /** {@code keyspace.object}, each part quoted only where required. */
  static String qualify(String keyspace, String object) {
    if (keyspace == null || keyspace.isEmpty()) {
      return quote(object);
    }
    return object == null || object.isEmpty() ? quote(keyspace) : quote(keyspace) + "." + quote(object);
  }

  /** A single-quoted CQL string literal with embedded quotes doubled. */
  static String literal(String value) {
    return "'" + (value == null ? "" : value.replace("'", "''")) + "'";
  }
}
