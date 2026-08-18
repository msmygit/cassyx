package io.cassyx.core.api.query;

import com.datastax.oss.driver.api.core.type.DataType;

/**
 * Translates between driver values and the JSON wire encoding fixed by the API contract.
 *
 * <p>Two rules here are load-bearing and both are places where getting it wrong corrupts data
 * silently rather than loudly:
 *
 * <ol>
 *   <li><b>{@code bigint}, {@code varint}, {@code counter}, {@code decimal} and token values travel
 *       as JSON strings.</b> They exceed {@code Number.MAX_SAFE_INTEGER}, so a JSON number would be
 *       rounded by every JavaScript client with no error anywhere.
 *   <li><b>{@code null} and <i>unset</i> are different.</b> {@code null} writes a tombstone; unset
 *       does not write the column at all. The wire sentinel is the literal string {@value #UNSET},
 *       and an absent key also means unset (plan section 7).
 * </ol>
 */
public interface CqlValueCodec {

  /** Wire sentinel for "leave this column unwritten". */
  String UNSET = "$unset";

  /** Marker returned by {@link #fromWire} for {@link #UNSET}; never equal to {@code null}. */
  Object UNSET_VALUE = new Object();

  /** Driver value to JSON-encodable value, per the contract's {@code CqlValue} table. */
  Object toWire(Object driverValue);

  /**
   * JSON-decoded value to a driver value suitable for binding.
   *
   * @param type the target column / bind-variable type
   * @return {@link #UNSET_VALUE} when the wire value was {@value #UNSET}
   */
  Object fromWire(Object wireValue, DataType type);

  /** Renders a value as a CQL literal for generated statements (never for binding user input). */
  String toLiteral(Object wireValue, DataType type);

  static boolean isUnset(Object value) {
    return value == UNSET_VALUE || UNSET.equals(value);
  }
}
