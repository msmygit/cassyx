package io.cassyx.bulk.impl;

import com.datastax.oss.driver.api.core.data.CqlDuration;
import com.datastax.oss.driver.api.core.data.CqlVector;
import com.datastax.oss.driver.api.core.data.TupleValue;
import com.datastax.oss.driver.api.core.data.UdtValue;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders driver values into the small set of Java types every encoder can handle.
 *
 * <p>The rule: primitives, {@link String}, {@link java.time}/{@link java.util.UUID} and friends pass
 * through unchanged; everything structural (collections, UDTs, tuples, vectors) becomes a
 * {@link List}/{@link Map} so the JSON, XML and Parquet encoders can walk it; blobs become
 * {@code 0x}-prefixed hex, which is what cqlsh prints and what a CQL literal accepts back.
 */
public final class CellValues {

  private static final char[] HEX = "0123456789abcdef".toCharArray();

  private CellValues() {}

  public static Object normalise(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof ByteBuffer buffer) {
      return toHex(buffer);
    }
    if (value instanceof CqlVector<?> vector) {
      List<Object> out = new ArrayList<>();
      vector.forEach(element -> out.add(normalise(element)));
      return out;
    }
    if (value instanceof Collection<?> collection) {
      List<Object> out = new ArrayList<>(collection.size());
      for (Object element : collection) {
        out.add(normalise(element));
      }
      return out;
    }
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> out = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        out.put(String.valueOf(normalise(entry.getKey())), normalise(entry.getValue()));
      }
      return out;
    }
    if (value instanceof UdtValue udt) {
      Map<String, Object> out = new LinkedHashMap<>();
      int size = udt.size();
      for (int i = 0; i < size; i++) {
        out.put(udt.getType().getFieldNames().get(i).asInternal(), normalise(udt.getObject(i)));
      }
      return out;
    }
    if (value instanceof TupleValue tuple) {
      List<Object> out = new ArrayList<>();
      for (int i = 0; i < tuple.size(); i++) {
        out.add(normalise(tuple.getObject(i)));
      }
      return out;
    }
    if (value instanceof CqlDuration duration) {
      return duration.toString();
    }
    return value;
  }

  /** {@code 0x}-prefixed lower-case hex, cqlsh style. Does not consume the buffer. */
  public static String toHex(ByteBuffer buffer) {
    ByteBuffer duplicate = buffer.duplicate();
    StringBuilder sb = new StringBuilder(2 + duplicate.remaining() * 2).append("0x");
    while (duplicate.hasRemaining()) {
      int b = duplicate.get() & 0xff;
      sb.append(HEX[b >>> 4]).append(HEX[b & 0x0f]);
    }
    return sb.toString();
  }

  /** Flat text rendering, for the row-oriented text formats (CSV, XLSX cells, XML text nodes). */
  public static String asText(Object value) {
    Object normalised = normalise(value);
    if (normalised == null) {
      return null;
    }
    if (normalised instanceof Collection<?> || normalised instanceof Map<?, ?>) {
      return Json.write(normalised);
    }
    return String.valueOf(normalised);
  }
}
