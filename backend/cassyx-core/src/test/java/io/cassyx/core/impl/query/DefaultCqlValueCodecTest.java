package io.cassyx.core.impl.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.datastax.oss.driver.api.core.CqlIdentifier;
import com.datastax.oss.driver.api.core.data.CqlDuration;
import com.datastax.oss.driver.api.core.data.TupleValue;
import com.datastax.oss.driver.api.core.data.UdtValue;
import com.datastax.oss.driver.api.core.detach.AttachmentPoint;
import com.datastax.oss.driver.api.core.type.DataTypes;
import com.datastax.oss.driver.api.core.type.TupleType;
import com.datastax.oss.driver.api.core.type.UserDefinedType;
import com.datastax.oss.driver.internal.core.type.DefaultTupleType;
import com.datastax.oss.driver.internal.core.type.UserDefinedTypeBuilder;
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
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DefaultCqlValueCodecTest {

  private final CqlValueCodec codec = new DefaultCqlValueCodec();

  /* ------------------------------------------------------------------------------- toWire */

  @Test
  @DisplayName("bigint / varint / decimal / counter go out as STRINGS, not JSON numbers")
  void bigNumbersTravelAsStrings() {
    // 2^53 + 1: the first integer a JSON number cannot represent. Sent as a number this arrives in
    // the browser as 9007199254740992 - silent, unreported corruption.
    assertThat(codec.toWire(9_007_199_254_740_993L)).isEqualTo("9007199254740993");
    assertThat(codec.toWire(new BigInteger("170141183460469231731687303715884105727")))
        .isEqualTo("170141183460469231731687303715884105727");
    assertThat(codec.toWire(new BigDecimal("1.7976931348623157E+309"))).isEqualTo("1.7976931348623157E+309");
  }

  @Test
  void smallNumbersAndBooleansStayNative() {
    assertThat(codec.toWire(42)).isEqualTo(42);
    assertThat(codec.toWire((short) 7)).isEqualTo((short) 7);
    assertThat(codec.toWire(1.5d)).isEqualTo(1.5d);
    assertThat(codec.toWire(true)).isEqualTo(true);
    assertThat(codec.toWire(null)).isNull();
  }

  @Test
  void blobsTravelAsBase64() {
    ByteBuffer blob = ByteBuffer.wrap("cassyx".getBytes(StandardCharsets.UTF_8));

    assertThat(codec.toWire(blob)).isEqualTo(Base64.getEncoder().encodeToString("cassyx".getBytes(
        StandardCharsets.UTF_8)));
    // The buffer must not be consumed: it is still needed by whoever handed it to us.
    assertThat(blob.remaining()).isEqualTo(6);
    assertThat(codec.toWire("ab".getBytes(StandardCharsets.UTF_8))).isEqualTo("YWI=");
  }

  @Test
  void temporalUuidAndInetTravelAsStrings() throws UnknownHostException {
    assertThat(codec.toWire(Instant.parse("2026-08-17T10:31:02Z"))).isEqualTo("2026-08-17T10:31:02Z");
    assertThat(codec.toWire(LocalDate.of(2026, 8, 17))).isEqualTo("2026-08-17");
    assertThat(codec.toWire(LocalTime.of(1, 2, 3))).isEqualTo("01:02:03");
    assertThat(codec.toWire(UUID.fromString("1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d")))
        .isEqualTo("1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d");
    assertThat(codec.toWire(CqlDuration.from("1h30m"))).isEqualTo("1h30m");
    assertThat(codec.toWire(InetAddress.getByName("10.0.0.1"))).isEqualTo("10.0.0.1");
  }

  @Test
  void collectionsRecurse() {
    assertThat(codec.toWire(List.of(1L, 2L))).isEqualTo(List.of("1", "2"));
    assertThat(codec.toWire(Set.of(5))).isEqualTo(List.of(5));
  }

  @Test
  @DisplayName("A map with non-string keys becomes an array of {key,value}, not a JSON object")
  void mapsEncodeByKeyType() {
    assertThat(codec.toWire(Map.of("a", 1L))).isEqualTo(Map.of("a", "1"));

    Map<Integer, String> intKeyed = new LinkedHashMap<>();
    intKeyed.put(1, "one");
    Object encoded = codec.toWire(intKeyed);

    assertThat(encoded).isInstanceOf(List.class);
    assertThat((List<?>) encoded).singleElement().isEqualTo(Map.of("key", 1, "value", "one"));
  }

  @Test
  void udtsBecomeObjectsAndTuplesBecomeArrays() {
    UserDefinedType address = addressType();
    UdtValue value = address.newValue().setString("street", "1 Main St").setInt("zip", 90210);

    assertThat(codec.toWire(value))
        .isEqualTo(new LinkedHashMap<>(Map.of("street", "1 Main St", "zip", 90210)));

    TupleType pair = tupleType();
    TupleValue tuple = pair.newValue().setInt(0, 1).setString(1, "two");
    assertThat(codec.toWire(tuple)).isEqualTo(List.of(1, "two"));
  }

  /* ----------------------------------------------------------------------------- fromWire */

  @Test
  @DisplayName("null and unset are different values, and stay different")
  void unsetIsNotNull() {
    assertThat(codec.fromWire(null, DataTypes.TEXT)).isNull();
    assertThat(codec.fromWire("$unset", DataTypes.TEXT)).isSameAs(CqlValueCodec.UNSET_VALUE);
    assertThat(CqlValueCodec.isUnset("$unset")).isTrue();
    assertThat(CqlValueCodec.isUnset(CqlValueCodec.UNSET_VALUE)).isTrue();
    assertThat(CqlValueCodec.isUnset(null)).isFalse();
    assertThat(CqlValueCodec.isUnset("")).isFalse();
  }

  @Test
  void decodesScalarsAgainstTheDeclaredType() {
    assertThat(codec.fromWire("9007199254740993", DataTypes.BIGINT)).isEqualTo(9_007_199_254_740_993L);
    assertThat(codec.fromWire(7, DataTypes.BIGINT)).isEqualTo(7L);
    assertThat(codec.fromWire("42", DataTypes.INT)).isEqualTo(42);
    assertThat(codec.fromWire(1, DataTypes.SMALLINT)).isEqualTo((short) 1);
    assertThat(codec.fromWire(1, DataTypes.TINYINT)).isEqualTo((byte) 1);
    assertThat(codec.fromWire("1.5", DataTypes.FLOAT)).isEqualTo(1.5f);
    assertThat(codec.fromWire(1.5, DataTypes.DOUBLE)).isEqualTo(1.5d);
    assertThat(codec.fromWire("1.25", DataTypes.DECIMAL)).isEqualTo(new BigDecimal("1.25"));
    assertThat(codec.fromWire("12", DataTypes.VARINT)).isEqualTo(BigInteger.valueOf(12));
    assertThat(codec.fromWire(true, DataTypes.BOOLEAN)).isEqualTo(true);
    assertThat(codec.fromWire("true", DataTypes.BOOLEAN)).isEqualTo(true);
    assertThat(codec.fromWire("2026-08-17T10:31:02Z", DataTypes.TIMESTAMP))
        .isEqualTo(Instant.parse("2026-08-17T10:31:02Z"));
    assertThat(codec.fromWire("1755424262000", DataTypes.TIMESTAMP))
        .isEqualTo(Instant.ofEpochMilli(1755424262000L));
    assertThat(codec.fromWire("2026-08-17", DataTypes.DATE)).isEqualTo(LocalDate.of(2026, 8, 17));
    assertThat(codec.fromWire("01:02:03", DataTypes.TIME)).isEqualTo(LocalTime.of(1, 2, 3));
    assertThat(codec.fromWire("10.0.0.1", DataTypes.INET)).isEqualTo(inet("10.0.0.1"));
    assertThat(codec.fromWire("1h", DataTypes.DURATION)).isEqualTo(CqlDuration.from("1h"));
    assertThat(codec.fromWire("hello", DataTypes.TEXT)).isEqualTo("hello");
    assertThat(codec.fromWire("hello", null)).isEqualTo("hello");
  }

  @Test
  void acceptsBothBase64AndHexForBlobs() {
    assertThat(codec.fromWire("YWI=", DataTypes.BLOB))
        .isEqualTo(ByteBuffer.wrap(new byte[] {'a', 'b'}));
    assertThat(codec.fromWire("0x6162", DataTypes.BLOB))
        .isEqualTo(ByteBuffer.wrap(new byte[] {'a', 'b'}));
  }

  @Test
  void badScalarsFailLoudlyWithTheTypeInTheMessage() {
    assertThatThrownBy(() -> codec.fromWire("not-a-number", DataTypes.BIGINT))
        .isInstanceOf(CassyxCoreException.class)
        .hasMessageContaining("bigint");
  }

  @Test
  void decodesCollectionsTuplesAndUdts() {
    assertThat(codec.fromWire(List.of("1", "2"), DataTypes.listOf(DataTypes.BIGINT)))
        .isEqualTo(List.of(1L, 2L));
    assertThat(codec.fromWire(List.of("1"), DataTypes.setOf(DataTypes.BIGINT)))
        .isEqualTo(Set.of(1L));
    assertThat(codec.fromWire(Map.of("a", "1"), DataTypes.mapOf(DataTypes.TEXT, DataTypes.BIGINT)))
        .isEqualTo(Map.of("a", 1L));
    assertThat(
            codec.fromWire(
                List.of(Map.of("key", "1", "value", "x")),
                DataTypes.mapOf(DataTypes.BIGINT, DataTypes.TEXT)))
        .isEqualTo(Map.of(1L, "x"));

    TupleValue tuple = (TupleValue) codec.fromWire(List.of(1, "two"), tupleType());
    assertThat(tuple.getInt(0)).isEqualTo(1);
    assertThat(tuple.getString(1)).isEqualTo("two");

    UdtValue udt = (UdtValue) codec.fromWire(Map.of("street", "1 Main St"), addressType());
    assertThat(udt.getString("street")).isEqualTo("1 Main St");
    assertThat(udt.isNull("zip")).isTrue();
  }

  @Test
  void rejectsMalformedStructuredValues() {
    assertThatThrownBy(() -> codec.fromWire("nope", addressType()))
        .isInstanceOf(CassyxCoreException.class)
        .hasMessageContaining("JSON object");
    assertThatThrownBy(() -> codec.fromWire("nope", DataTypes.listOf(DataTypes.TEXT)))
        .isInstanceOf(CassyxCoreException.class)
        .hasMessageContaining("JSON array");
  }

  /* ---------------------------------------------------------------------------- toLiteral */

  @Test
  void rendersLiteralsCorrectlyPerType() {
    assertThat(codec.toLiteral("O'Brien", DataTypes.TEXT)).isEqualTo("'O''Brien'");
    assertThat(codec.toLiteral(null, DataTypes.TEXT)).isEqualTo("null");
    assertThat(codec.toLiteral("9007199254740993", DataTypes.BIGINT)).isEqualTo("9007199254740993");
    assertThat(codec.toLiteral(42, DataTypes.INT)).isEqualTo("42");
    assertThat(codec.toLiteral(true, DataTypes.BOOLEAN)).isEqualTo("true");
    assertThat(codec.toLiteral("1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d", DataTypes.UUID))
        .isEqualTo("1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d");
    assertThat(codec.toLiteral("2026-08-17T10:31:02Z", DataTypes.TIMESTAMP))
        .isEqualTo("'2026-08-17T10:31:02Z'");
    assertThat(codec.toLiteral("YWI=", DataTypes.BLOB)).isEqualTo("0x6162");
    assertThat(codec.toLiteral(List.of("1", "2"), DataTypes.listOf(DataTypes.BIGINT)))
        .isEqualTo("[1, 2]");
    assertThat(codec.toLiteral(List.of(), DataTypes.listOf(DataTypes.BIGINT))).isEqualTo("[]");
    assertThat(codec.toLiteral(List.of("a"), DataTypes.setOf(DataTypes.TEXT))).isEqualTo("{'a'}");
    assertThat(codec.toLiteral(List.of(), DataTypes.setOf(DataTypes.TEXT))).isEqualTo("{}");
    assertThat(codec.toLiteral(Map.of("a", "1"), DataTypes.mapOf(DataTypes.TEXT, DataTypes.BIGINT)))
        .isEqualTo("{'a': 1}");
    assertThat(
            codec.toLiteral(
                List.of(Map.of("key", "1", "value", "x")),
                DataTypes.mapOf(DataTypes.BIGINT, DataTypes.TEXT)))
        .isEqualTo("{1: 'x'}");
    assertThat(codec.toLiteral(List.of(1, "two"), tupleType())).isEqualTo("(1, 'two')");
    assertThat(codec.toLiteral(Map.of("street", "Main"), addressType())).isEqualTo("{street: 'Main'}");
  }

  @Test
  void anUnsetValueHasNoLiteral() {
    assertThatThrownBy(() -> codec.toLiteral("$unset", DataTypes.TEXT))
        .isInstanceOf(CassyxCoreException.class)
        .hasMessageContaining("omit the column");
  }

  @Test
  void aBadUuidLiteralIsRejectedRatherThanPastedIntoCql() {
    assertThatThrownBy(() -> codec.toLiteral("'; DROP TABLE users; --", DataTypes.UUID))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /* ------------------------------------------------------------------------------ helpers */

  private static UserDefinedType addressType() {
    return new UserDefinedTypeBuilder(CqlIdentifier.fromInternal("demo"), CqlIdentifier.fromInternal("address"))
        .withField(CqlIdentifier.fromInternal("street"), DataTypes.TEXT)
        .withField(CqlIdentifier.fromInternal("zip"), DataTypes.INT)
        .build()
        .copy(true);
  }

  private static TupleType tupleType() {
    return new DefaultTupleType(List.of(DataTypes.INT, DataTypes.TEXT), AttachmentPoint.NONE);
  }

  private static InetAddress inet(String host) {
    try {
      return InetAddress.getByName(host);
    } catch (UnknownHostException e) {
      throw new IllegalStateException(e);
    }
  }
}
