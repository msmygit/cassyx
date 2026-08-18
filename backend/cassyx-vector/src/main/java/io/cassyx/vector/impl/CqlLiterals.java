package io.cassyx.vector.impl;

import io.cassyx.vector.api.VectorException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collection;
import java.util.Map;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * CQL identifier quoting and literal rendering.
 *
 * <p>Everything the ANN builder inlines goes through here. Identifiers that are not already valid
 * unquoted CQL are double-quoted; string literals are single-quoted with {@code ''} escaping. An
 * identifier that cannot be quoted safely - because it contains something a quote cannot survive -
 * is rejected rather than emitted, since the whole point of the preview pane is that the user reads
 * the statement before it runs.
 */
final class CqlLiterals {

  /** Unquoted CQL identifiers are lowercase alphanumerics and underscores, not starting with a digit. */
  private static final Pattern UNQUOTED = Pattern.compile("[a-z][a-z0-9_]*");

  /** What we are willing to put inside double quotes: no quote characters, no control characters. */
  private static final Pattern QUOTABLE = Pattern.compile("[^\"\\\\\\p{Cntrl}]+");

  /** Collection selectors the SAI target syntax allows, e.g. {@code values(tags)}. */
  private static final Pattern SELECTOR =
      Pattern.compile("(?i)(values|keys|entries|full)\\s*\\(\\s*([^()\"'\\\\\\p{Cntrl}]+)\\s*\\)");

  private CqlLiterals() {}

  /** Quotes an identifier only when it has to be quoted, which keeps generated CQL readable. */
  static String identifier(String name) {
    if (name == null || name.isBlank()) {
      throw new VectorException("A CQL identifier is required");
    }
    String trimmed = name.trim();
    if (UNQUOTED.matcher(trimmed).matches()) {
      return trimmed;
    }
    if (!QUOTABLE.matcher(trimmed).matches()) {
      throw new VectorException("Illegal CQL identifier: " + name);
    }
    return '"' + trimmed + '"';
  }

  /** {@code ks.tbl}, each part quoted as needed. */
  static String qualified(String keyspace, String table) {
    return identifier(keyspace) + "." + identifier(table);
  }

  /**
   * An SAI index target: a plain column, or a collection selector such as {@code values(tags)}
   * (which is CQL syntax, so it cannot be quoted wholesale).
   */
  static String indexTarget(String target) {
    if (target == null || target.isBlank()) {
      throw new VectorException("An index target is required");
    }
    String trimmed = target.trim();
    var matcher = SELECTOR.matcher(trimmed);
    if (matcher.matches()) {
      return matcher.group(1).toLowerCase(java.util.Locale.ROOT)
          + "("
          + identifier(matcher.group(2).trim())
          + ")";
    }
    return identifier(trimmed);
  }

  /** A single-quoted CQL string with {@code ''} escaping. */
  static String stringLiteral(String value) {
    return "'" + value.replace("'", "''") + "'";
  }

  /** Renders any supported predicate value as a CQL literal. */
  static String literal(Object value) {
    if (value == null) {
      return "NULL";
    }
    if (value instanceof Boolean || value instanceof Number) {
      return value.toString();
    }
    if (value instanceof InetAddress address) {
      // InetAddress.toString() is "hostname/1.2.3.4"; CQL wants the address alone.
      return stringLiteral(address.getHostAddress());
    }
    if (value instanceof UUID
        || value instanceof Instant
        || value instanceof LocalDate
        || value instanceof LocalTime) {
      return stringLiteral(value.toString());
    }
    if (value instanceof ByteBuffer) {
      throw new VectorException("Blob predicates are not supported in the ANN builder");
    }
    if (value instanceof Map<?, ?> map) {
      StringJoiner joiner = new StringJoiner(", ", "{", "}");
      map.forEach((k, v) -> joiner.add(literal(k) + ": " + literal(v)));
      return joiner.toString();
    }
    if (value instanceof Collection<?> collection) {
      StringJoiner joiner = new StringJoiner(", ", "{", "}");
      collection.forEach(element -> joiner.add(literal(element)));
      return joiner.toString();
    }
    return stringLiteral(value.toString());
  }

  /** The right-hand side of {@code IN}: a parenthesised list rather than a set literal. */
  static String inList(Collection<?> values) {
    if (values == null || values.isEmpty()) {
      throw new VectorException("The IN operator needs at least one value");
    }
    StringJoiner joiner = new StringJoiner(", ", "(", ")");
    values.forEach(value -> joiner.add(literal(value)));
    return joiner.toString();
  }
}
