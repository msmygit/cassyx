package io.cassyx.core.impl.query;

import com.datastax.oss.driver.api.core.CqlIdentifier;
import com.datastax.oss.driver.api.core.data.CqlDuration;
import com.datastax.oss.driver.api.core.data.CqlVector;
import com.datastax.oss.driver.api.core.data.TupleValue;
import com.datastax.oss.driver.api.core.data.UdtValue;
import com.datastax.oss.driver.api.core.type.DataType;
import com.datastax.oss.driver.api.core.type.ListType;
import com.datastax.oss.driver.api.core.type.MapType;
import com.datastax.oss.driver.api.core.type.SetType;
import com.datastax.oss.driver.api.core.type.TupleType;
import com.datastax.oss.driver.api.core.type.UserDefinedType;
import com.datastax.oss.protocol.internal.ProtocolConstants;
import io.cassyx.core.api.CassyxCoreException;
import io.cassyx.core.api.query.CqlValueCodec;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The wire codec fixed by the API contract.
 *
 * <p>Encoding keys off the RUNTIME value class rather than the declared type, because the driver has
 * already produced a proper Java object by then and the value class is unambiguous. Decoding and
 * literal rendering key off the declared {@link DataType}, because {@code "42"} is a different value
 * for a {@code bigint} column than for a {@code text} one and only the schema knows which.
 */
public final class DefaultCqlValueCodec implements CqlValueCodec {

  private static final Set<Integer> STRINGY_CODES =
      Set.of(
          ProtocolConstants.DataType.ASCII,
          ProtocolConstants.DataType.VARCHAR,
          ProtocolConstants.DataType.INET,
          ProtocolConstants.DataType.DURATION,
          ProtocolConstants.DataType.DATE,
          ProtocolConstants.DataType.TIME,
          ProtocolConstants.DataType.TIMESTAMP,
          ProtocolConstants.DataType.UUID,
          ProtocolConstants.DataType.TIMEUUID);

  @Override
  public Object toWire(Object value) {
    if (value == null) {
      return null;
    }
    // Values that exceed Number.MAX_SAFE_INTEGER go out as strings. A JSON number here would be
    // silently rounded by every JavaScript client, with no error raised anywhere.
    if (value instanceof Long
        || value instanceof BigInteger
        || value instanceof BigDecimal) {
      return value.toString();
    }
    if (value instanceof Number || value instanceof Boolean) {
      return value;
    }
    if (value instanceof ByteBuffer buffer) {
      return Base64.getEncoder().encodeToString(bytes(buffer));
    }
    if (value instanceof byte[] raw) {
      return Base64.getEncoder().encodeToString(raw);
    }
    if (value instanceof Instant
        || value instanceof LocalDate
        || value instanceof LocalTime
        || value instanceof UUID
        || value instanceof CqlDuration
        || value instanceof CharSequence) {
      return value.toString();
    }
    if (value instanceof InetAddress address) {
      return address.getHostAddress();
    }
    if (value instanceof CqlVector<?> vector) {
      List<Object> out = new ArrayList<>();
      vector.forEach(element -> out.add(toWire(element)));
      return out;
    }
    if (value instanceof List<?> list) {
      return list.stream().map(this::toWire).toList();
    }
    if (value instanceof Set<?> set) {
      return set.stream().map(this::toWire).toList();
    }
    if (value instanceof Map<?, ?> map) {
      return toWireMap(map);
    }
    if (value instanceof UdtValue udt) {
      Map<String, Object> out = new LinkedHashMap<>();
      for (CqlIdentifier field : udt.getType().getFieldNames()) {
        out.put(field.asInternal(), toWire(udt.getObject(field)));
      }
      return out;
    }
    if (value instanceof TupleValue tuple) {
      List<Object> out = new ArrayList<>();
      for (int i = 0; i < tuple.getType().getComponentTypes().size(); i++) {
        out.add(toWire(tuple.getObject(i)));
      }
      return out;
    }
    return value.toString();
  }

  /**
   * A CQL map with non-string keys cannot be a JSON object, so it goes out as an array of
   * {@code {key, value}} pairs - which the contract's {@code CqlValue} table specifies.
   */
  private Object toWireMap(Map<?, ?> map) {
    boolean stringKeys = map.keySet().stream().allMatch(k -> k instanceof CharSequence);
    if (stringKeys) {
      Map<String, Object> out = new LinkedHashMap<>();
      map.forEach((k, v) -> out.put(k.toString(), toWire(v)));
      return out;
    }
    List<Object> out = new ArrayList<>();
    map.forEach(
        (k, v) -> {
          Map<String, Object> pair = new LinkedHashMap<>();
          pair.put("key", toWire(k));
          pair.put("value", toWire(v));
          out.add(pair);
        });
    return out;
  }

  @Override
  public Object fromWire(Object wire, DataType type) {
    if (CqlValueCodec.isUnset(wire)) {
      return UNSET_VALUE;
    }
    if (wire == null) {
      return null;
    }
    if (type == null) {
      return wire;
    }

    if (type instanceof ListType list) {
      return asList(wire).stream().map(e -> fromWire(e, list.getElementType())).toList();
    }
    if (type instanceof SetType set) {
      return new java.util.LinkedHashSet<>(
          asList(wire).stream().map(e -> fromWire(e, set.getElementType())).toList());
    }
    if (type instanceof MapType map) {
      return fromWireMap(wire, map);
    }
    if (type instanceof TupleType tuple) {
      List<?> items = asList(wire);
      TupleValue value = tuple.newValue();
      for (int i = 0; i < tuple.getComponentTypes().size() && i < items.size(); i++) {
        DataType componentType = tuple.getComponentTypes().get(i);
        value = value.set(i, fromWire(items.get(i), componentType), rawClass(componentType));
      }
      return value;
    }
    if (type instanceof UserDefinedType udt) {
      return fromWireUdt(wire, udt);
    }

    return fromWireScalar(wire, type);
  }

  private Object fromWireMap(Object wire, MapType map) {
    Map<Object, Object> out = new LinkedHashMap<>();
    if (wire instanceof Map<?, ?> jsonMap) {
      jsonMap.forEach(
          (k, v) -> out.put(fromWire(k, map.getKeyType()), fromWire(v, map.getValueType())));
      return out;
    }
    for (Object entry : asList(wire)) {
      if (entry instanceof Map<?, ?> pair) {
        out.put(fromWire(pair.get("key"), map.getKeyType()), fromWire(pair.get("value"), map.getValueType()));
      }
    }
    return out;
  }

  private Object fromWireUdt(Object wire, UserDefinedType udt) {
    if (!(wire instanceof Map<?, ?> fields)) {
      throw new CassyxCoreException("A UDT value must be a JSON object, got " + wire.getClass().getSimpleName());
    }
    UdtValue value = udt.newValue();
    for (int i = 0; i < udt.getFieldNames().size(); i++) {
      CqlIdentifier name = udt.getFieldNames().get(i);
      if (!fields.containsKey(name.asInternal())) {
        continue;
      }
      DataType fieldType = udt.getFieldTypes().get(i);
      Object decoded = fromWire(fields.get(name.asInternal()), fieldType);
      value = value.set(i, decoded == UNSET_VALUE ? null : decoded, rawClass(fieldType));
    }
    return value;
  }

  @SuppressWarnings("unchecked")
  private static <T> Class<T> rawClass(DataType type) {
    return (Class<T>) javaClass(type);
  }

  private static Class<?> javaClass(DataType type) {
    if (type instanceof ListType) {
      return List.class;
    }
    if (type instanceof SetType) {
      return Set.class;
    }
    if (type instanceof MapType) {
      return Map.class;
    }
    if (type instanceof TupleType) {
      return TupleValue.class;
    }
    if (type instanceof UserDefinedType) {
      return UdtValue.class;
    }
    return switch (type.getProtocolCode()) {
      case ProtocolConstants.DataType.BIGINT, ProtocolConstants.DataType.COUNTER -> Long.class;
      case ProtocolConstants.DataType.INT -> Integer.class;
      case ProtocolConstants.DataType.SMALLINT -> Short.class;
      case ProtocolConstants.DataType.TINYINT -> Byte.class;
      case ProtocolConstants.DataType.FLOAT -> Float.class;
      case ProtocolConstants.DataType.DOUBLE -> Double.class;
      case ProtocolConstants.DataType.BOOLEAN -> Boolean.class;
      case ProtocolConstants.DataType.DECIMAL -> BigDecimal.class;
      case ProtocolConstants.DataType.VARINT -> BigInteger.class;
      case ProtocolConstants.DataType.BLOB -> ByteBuffer.class;
      case ProtocolConstants.DataType.UUID, ProtocolConstants.DataType.TIMEUUID -> UUID.class;
      case ProtocolConstants.DataType.TIMESTAMP -> Instant.class;
      case ProtocolConstants.DataType.DATE -> LocalDate.class;
      case ProtocolConstants.DataType.TIME -> LocalTime.class;
      case ProtocolConstants.DataType.INET -> InetAddress.class;
      case ProtocolConstants.DataType.DURATION -> CqlDuration.class;
      default -> String.class;
    };
  }

  private Object fromWireScalar(Object wire, DataType type) {
    String text = wire instanceof CharSequence ? wire.toString() : null;
    try {
      return switch (type.getProtocolCode()) {
        case ProtocolConstants.DataType.BIGINT, ProtocolConstants.DataType.COUNTER ->
            text != null ? Long.valueOf(text.trim()) : ((Number) wire).longValue();
        case ProtocolConstants.DataType.INT ->
            text != null ? Integer.valueOf(text.trim()) : ((Number) wire).intValue();
        case ProtocolConstants.DataType.SMALLINT ->
            text != null ? Short.valueOf(text.trim()) : ((Number) wire).shortValue();
        case ProtocolConstants.DataType.TINYINT ->
            text != null ? Byte.valueOf(text.trim()) : ((Number) wire).byteValue();
        case ProtocolConstants.DataType.FLOAT ->
            text != null ? Float.valueOf(text.trim()) : ((Number) wire).floatValue();
        case ProtocolConstants.DataType.DOUBLE ->
            text != null ? Double.valueOf(text.trim()) : ((Number) wire).doubleValue();
        case ProtocolConstants.DataType.DECIMAL -> new BigDecimal(String.valueOf(wire).trim());
        case ProtocolConstants.DataType.VARINT -> new BigInteger(String.valueOf(wire).trim());
        case ProtocolConstants.DataType.BOOLEAN ->
            wire instanceof Boolean b ? b : Boolean.valueOf(String.valueOf(wire).trim());
        case ProtocolConstants.DataType.BLOB -> ByteBuffer.wrap(decodeBlob(String.valueOf(wire)));
        case ProtocolConstants.DataType.UUID, ProtocolConstants.DataType.TIMEUUID ->
            UUID.fromString(String.valueOf(wire).trim());
        case ProtocolConstants.DataType.TIMESTAMP -> parseInstant(String.valueOf(wire).trim());
        case ProtocolConstants.DataType.DATE -> LocalDate.parse(String.valueOf(wire).trim());
        case ProtocolConstants.DataType.TIME -> LocalTime.parse(String.valueOf(wire).trim());
        case ProtocolConstants.DataType.INET -> InetAddress.getByName(String.valueOf(wire).trim());
        case ProtocolConstants.DataType.DURATION -> CqlDuration.from(String.valueOf(wire).trim());
        default -> String.valueOf(wire);
      };
    } catch (DateTimeParseException
        | UnknownHostException
        | IllegalArgumentException
        | ClassCastException e) {
      throw new CassyxCoreException(
          "Cannot read '" + wire + "' as " + type.asCql(true, false) + ": " + e.getMessage(), e);
    }
  }

  private static Instant parseInstant(String text) {
    try {
      return Instant.parse(text);
    } catch (DateTimeParseException e) {
      return Instant.ofEpochMilli(Long.parseLong(text));
    }
  }

  /** Blobs travel as base64 but users paste {@code 0x…} hex, so both are accepted on the way in. */
  private static byte[] decodeBlob(String text) {
    String trimmed = text.trim();
    if (trimmed.startsWith("0x") || trimmed.startsWith("0X")) {
      return HexFormat.of().parseHex(trimmed.substring(2));
    }
    return Base64.getDecoder().decode(trimmed);
  }

  @Override
  public String toLiteral(Object wire, DataType type) {
    if (CqlValueCodec.isUnset(wire)) {
      throw new CassyxCoreException("An unset value has no CQL literal; omit the column instead.");
    }
    if (wire == null) {
      return "null";
    }
    if (type instanceof ListType list) {
      return asList(wire).stream().map(e -> toLiteral(e, list.getElementType()))
          .reduce((a, b) -> a + ", " + b).map(s -> "[" + s + "]").orElse("[]");
    }
    if (type instanceof SetType set) {
      return asList(wire).stream().map(e -> toLiteral(e, set.getElementType()))
          .reduce((a, b) -> a + ", " + b).map(s -> "{" + s + "}").orElse("{}");
    }
    if (type instanceof MapType map) {
      return mapLiteral(wire, map);
    }
    if (type instanceof TupleType tuple) {
      List<?> items = asList(wire);
      List<String> parts = new ArrayList<>();
      for (int i = 0; i < tuple.getComponentTypes().size(); i++) {
        parts.add(toLiteral(i < items.size() ? items.get(i) : null, tuple.getComponentTypes().get(i)));
      }
      return "(" + String.join(", ", parts) + ")";
    }
    if (type instanceof UserDefinedType udt) {
      return udtLiteral(wire, udt);
    }
    return scalarLiteral(wire, type);
  }

  private String mapLiteral(Object wire, MapType map) {
    List<String> parts = new ArrayList<>();
    if (wire instanceof Map<?, ?> jsonMap) {
      jsonMap.forEach(
          (k, v) -> parts.add(toLiteral(k, map.getKeyType()) + ": " + toLiteral(v, map.getValueType())));
    } else {
      for (Object entry : asList(wire)) {
        if (entry instanceof Map<?, ?> pair) {
          parts.add(
              toLiteral(pair.get("key"), map.getKeyType())
                  + ": "
                  + toLiteral(pair.get("value"), map.getValueType()));
        }
      }
    }
    return "{" + String.join(", ", parts) + "}";
  }

  private String udtLiteral(Object wire, UserDefinedType udt) {
    if (!(wire instanceof Map<?, ?> fields)) {
      throw new CassyxCoreException("A UDT value must be a JSON object.");
    }
    List<String> parts = new ArrayList<>();
    for (int i = 0; i < udt.getFieldNames().size(); i++) {
      CqlIdentifier name = udt.getFieldNames().get(i);
      if (!fields.containsKey(name.asInternal())) {
        continue;
      }
      parts.add(
          name.asCql(true) + ": " + toLiteral(fields.get(name.asInternal()), udt.getFieldTypes().get(i)));
    }
    return "{" + String.join(", ", parts) + "}";
  }

  private static String scalarLiteral(Object wire, DataType type) {
    int code = type == null ? ProtocolConstants.DataType.VARCHAR : type.getProtocolCode();
    if (code == ProtocolConstants.DataType.BLOB) {
      return "0x" + HexFormat.of().formatHex(decodeBlob(String.valueOf(wire)));
    }
    if (code == ProtocolConstants.DataType.BOOLEAN) {
      return String.valueOf(wire instanceof Boolean b ? b : Boolean.valueOf(String.valueOf(wire)));
    }
    if (code == ProtocolConstants.DataType.UUID || code == ProtocolConstants.DataType.TIMEUUID) {
      // Validate rather than trust: an unquoted literal is the one place a bad value would be
      // pasted straight into executable CQL.
      return UUID.fromString(String.valueOf(wire).trim()).toString();
    }
    if (STRINGY_CODES.contains(code)) {
      return quote(String.valueOf(wire));
    }
    if (wire instanceof CharSequence) {
      return String.valueOf(wire).trim();
    }
    return String.valueOf(wire);
  }

  /** Single-quoted with {@code ''} escaping - the only correct way to embed text in CQL. */
  static String quote(String text) {
    return "'" + text.replace("'", "''") + "'";
  }

  private static List<?> asList(Object wire) {
    if (wire instanceof List<?> list) {
      return list;
    }
    if (wire instanceof Set<?> set) {
      return List.copyOf(set);
    }
    if (wire instanceof Object[] array) {
      return List.of(array);
    }
    throw new CassyxCoreException("Expected a JSON array, got " + wire.getClass().getSimpleName());
  }

  private static byte[] bytes(ByteBuffer buffer) {
    ByteBuffer copy = buffer.duplicate();
    byte[] out = new byte[copy.remaining()];
    copy.get(out);
    return out;
  }

  /** Reads a UTF-8 text value out of a buffer; used by the trace reader. */
  static String utf8(ByteBuffer buffer) {
    return new String(bytes(buffer), StandardCharsets.UTF_8);
  }
}
