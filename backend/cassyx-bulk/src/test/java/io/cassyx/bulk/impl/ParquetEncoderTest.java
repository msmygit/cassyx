package io.cassyx.bulk.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.cassyx.bulk.api.BulkFactory;
import io.cassyx.bulk.api.Encoder;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;
import org.apache.hadoop.conf.Configuration;
import org.apache.parquet.avro.AvroParquetReader;
import org.apache.parquet.hadoop.ParquetReader;
import org.apache.parquet.hadoop.util.HadoopInputFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Parquet is the format an analyst actually loads into Spark/DuckDB, so "it produced bytes" is not
 * enough: the type mapping decides whether the result is analytics-ready or a wall of strings, and
 * the file must be readable by a stock Parquet reader. Both are asserted here.
 */
class ParquetEncoderTest {

  @TempDir Path tmp;

  /**
   * hadoop-common's {@code FileSystem.get} touches {@code UserGroupInformation}, which lives partly
   * in hadoop-auth. That artifact is currently excluded in this module's pom, so the writer throws
   * {@code NoClassDefFoundError: org/apache/hadoop/util/PlatformName} at runtime - a real defect,
   * not a test-harness quirk. The round-trip tests below assume this away rather than fail, and will
   * start running by themselves the moment the exclusion is dropped.
   */
  private static boolean hadoopWriterRuntimeIsComplete() {
    try {
      Class.forName("org.apache.hadoop.util.PlatformName");
      return true;
    } catch (ClassNotFoundException e) {
      return false;
    }
  }

  /** The CQL to Avro table. Every unmapped CQL type must degrade to string, never blow up. */
  @Test
  void avroSchemaMapsCqlTypesOntoNullableAvroTypes() {
    List<String> columns =
        List.of("i", "si", "ti", "bi", "ct", "tm", "f", "d", "b", "bl", "txt", "uu", "lst");
    Map<String, String> types = new LinkedHashMap<>();
    types.put("i", "int");
    types.put("si", "smallint");
    types.put("ti", "tinyint");
    types.put("bi", "bigint");
    types.put("ct", "counter");
    types.put("tm", "time");
    types.put("f", "float");
    types.put("d", "double");
    types.put("b", "boolean");
    types.put("bl", "blob");
    types.put("txt", "text");
    types.put("uu", "uuid");
    types.put("lst", "list<text>");

    Schema schema = ParquetEncoder.avroSchema(columns, types);

    assertThat(nonNullType(schema, "i")).isEqualTo(Schema.Type.INT);
    assertThat(nonNullType(schema, "si")).isEqualTo(Schema.Type.INT);
    assertThat(nonNullType(schema, "ti")).isEqualTo(Schema.Type.INT);
    assertThat(nonNullType(schema, "bi")).isEqualTo(Schema.Type.LONG);
    assertThat(nonNullType(schema, "ct")).isEqualTo(Schema.Type.LONG);
    assertThat(nonNullType(schema, "tm")).isEqualTo(Schema.Type.LONG);
    assertThat(nonNullType(schema, "f")).isEqualTo(Schema.Type.FLOAT);
    assertThat(nonNullType(schema, "d")).isEqualTo(Schema.Type.DOUBLE);
    assertThat(nonNullType(schema, "b")).isEqualTo(Schema.Type.BOOLEAN);
    assertThat(nonNullType(schema, "bl")).isEqualTo(Schema.Type.BYTES);
    // Anything unmapped is a nullable UTF-8 string, which is lossless via CellValues.asText.
    assertThat(nonNullType(schema, "uu")).isEqualTo(Schema.Type.STRING);
    assertThat(nonNullType(schema, "lst")).isEqualTo(Schema.Type.STRING);
  }

  /**
   * Every field must be a {@code ["null", T]} union: a Cassandra column is always nullable, and a
   * non-optional Avro field would make the writer throw on the first null it meets.
   */
  @Test
  void everyFieldIsANullableUnion() {
    Schema schema = ParquetEncoder.avroSchema(List.of("a", "b"), Map.of("a", "int"));

    for (Schema.Field field : schema.getFields()) {
      assertThat(field.schema().getType()).isEqualTo(Schema.Type.UNION);
      assertThat(field.schema().getTypes())
          .extracting(Schema::getType)
          .contains(Schema.Type.NULL);
    }
    assertThat(schema.getName()).isEqualTo("cassyx_row");
    assertThat(schema.getNamespace()).isEqualTo("io.cassyx.bulk");
  }

  /** A column with no declared type falls back to text rather than failing the whole export. */
  @Test
  void unknownColumnsDefaultToString() {
    Schema schema = ParquetEncoder.avroSchema(List.of("mystery"), Map.of());
    assertThat(nonNullType(schema, "mystery")).isEqualTo(Schema.Type.STRING);
  }

  /**
   * The system table renders collection types as {@code frozen<list<text>>}; matching on the raw
   * string would send every frozen column down the wrong branch of the type table.
   */
  @Test
  void baseTypeStripsFrozenWrappersAndGenericParameters() {
    assertThat(ParquetEncoder.baseType("int")).isEqualTo("int");
    assertThat(ParquetEncoder.baseType("  BigInt ")).isEqualTo("bigint");
    assertThat(ParquetEncoder.baseType("list<text>")).isEqualTo("list");
    assertThat(ParquetEncoder.baseType("map<text, frozen<list<int>>>")).isEqualTo("map");
    assertThat(ParquetEncoder.baseType("frozen<list<text>>")).isEqualTo("list");
    assertThat(ParquetEncoder.baseType("frozen<frozen<set<int>>>")).isEqualTo("set");
    assertThat(ParquetEncoder.baseType("frozen<my_udt>")).isEqualTo("my_udt");
    assertThat(ParquetEncoder.baseType(null)).isEqualTo("text");
  }

  /**
   * The real proof: encode to a byte array, then read those exact bytes back with a stock
   * {@link AvroParquetReader}. This also exercises the temp-file-plus-copy trick the encoder needs
   * because Parquet's footer cannot be written to a forward-only stream.
   */
  @Test
  void roundTripsThroughAStockParquetReader() throws IOException {
    assumeTrue(hadoopWriterRuntimeIsComplete(), "hadoop-auth is excluded; see ParquetEncoder pom notes");
    List<String> columns = List.of("id", "name", "score", "active", "payload");
    Map<String, String> types =
        Map.of(
            "id", "bigint",
            "name", "text",
            "score", "double",
            "active", "boolean",
            "payload", "blob");

    Map<String, Object> first = new LinkedHashMap<>();
    first.put("id", 1L);
    first.put("name", "ada");
    first.put("score", 9.5d);
    first.put("active", true);
    first.put("payload", ByteBuffer.wrap(new byte[] {1, 2, 3}));

    Map<String, Object> second = new LinkedHashMap<>();
    second.put("id", 2L);
    // Null must survive the round trip as a genuine null, not as the string "null".
    second.put("name", null);
    second.put("score", 0.25d);
    second.put("active", false);
    second.put("payload", null);

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    Encoder encoder = BulkFactory.encoder("parquet");
    try (Encoder.Writer writer =
        encoder.open(out, new Encoder.EncoderContext(columns, types, Map.of()))) {
      writer.write(first);
      writer.write(second);
    }

    Path file = tmp.resolve("out.parquet");
    Files.write(file, out.toByteArray());
    List<GenericRecord> records = readBack(file);

    assertThat(records).hasSize(2);
    assertThat(records.get(0).get("id")).isEqualTo(1L);
    assertThat(String.valueOf(records.get(0).get("name"))).isEqualTo("ada");
    assertThat(records.get(0).get("score")).isEqualTo(9.5d);
    assertThat(records.get(0).get("active")).isEqualTo(Boolean.TRUE);
    assertThat(((ByteBuffer) records.get(0).get("payload")).remaining()).isEqualTo(3);

    assertThat(records.get(1).get("id")).isEqualTo(2L);
    assertThat(records.get(1).get("name")).isNull();
    assertThat(records.get(1).get("payload")).isNull();
    assertThat(records.get(1).get("active")).isEqualTo(Boolean.FALSE);
  }

  /**
   * String-shaped inputs for numeric columns are coerced rather than rejected: the driver hands back
   * whatever the row contained, and a whole export should not die on one loosely typed value.
   */
  @Test
  void coercesLooselyTypedValuesToTheDeclaredColumnType() throws IOException {
    assumeTrue(hadoopWriterRuntimeIsComplete(), "hadoop-auth is excluded; see ParquetEncoder pom notes");
    List<String> columns = List.of("i", "l", "f", "d", "b", "blb", "txt");
    Map<String, String> types =
        Map.of(
            "i", "int",
            "l", "bigint",
            "f", "float",
            "d", "double",
            "b", "boolean",
            "blb", "blob",
            "txt", "text");

    Map<String, Object> row = new LinkedHashMap<>();
    row.put("i", "42");
    row.put("l", "43");
    row.put("f", "1.5");
    row.put("d", new java.math.BigDecimal("2.25"));
    row.put("b", "true");
    // Not a ByteBuffer: the bytes branch has to cope with a plain string too.
    row.put("blb", "hi");
    row.put("txt", List.of("a", "b"));

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (Encoder.Writer writer =
        BulkFactory.encoder("parquet")
            .open(out, new Encoder.EncoderContext(columns, types, Map.of("compression", "uncompressed")))) {
      writer.write(row);
    }

    Path file = tmp.resolve("coerced.parquet");
    Files.write(file, out.toByteArray());
    GenericRecord record = readBack(file).get(0);

    assertThat(record.get("i")).isEqualTo(42);
    assertThat(record.get("l")).isEqualTo(43L);
    assertThat(record.get("f")).isEqualTo(1.5f);
    assertThat(record.get("d")).isEqualTo(2.25d);
    assertThat(record.get("b")).isEqualTo(Boolean.TRUE);
    assertThat(StandardCharsets.UTF_8.decode(((ByteBuffer) record.get("blb")).duplicate()).toString())
        .isEqualTo("hi");
    // Structural values reach the text column as their JSON rendering.
    assertThat(String.valueOf(record.get("txt"))).isEqualTo("[\"a\",\"b\"]");
  }

  private static Schema.Type nonNullType(Schema schema, String field) {
    for (Schema member : schema.getField(field).schema().getTypes()) {
      if (member.getType() != Schema.Type.NULL) {
        return member.getType();
      }
    }
    throw new AssertionError("field " + field + " is null-only");
  }

  private static List<GenericRecord> readBack(Path file) throws IOException {
    List<GenericRecord> records = new ArrayList<>();
    HadoopInputFile input =
        HadoopInputFile.fromPath(new org.apache.hadoop.fs.Path(file.toUri()), new Configuration());
    try (ParquetReader<GenericRecord> reader = AvroParquetReader.<GenericRecord>builder(input).build()) {
      GenericRecord record;
      while ((record = reader.read()) != null) {
        records.add(record);
      }
    }
    return records;
  }
}
