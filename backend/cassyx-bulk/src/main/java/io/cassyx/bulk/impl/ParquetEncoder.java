package io.cassyx.bulk.impl;

import io.cassyx.bulk.api.Encoder;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.avro.Schema;
import org.apache.avro.SchemaBuilder;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.hadoop.conf.Configuration;
import org.apache.parquet.avro.AvroParquetWriter;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.hadoop.util.HadoopOutputFile;

/**
 * Parquet encoder - cassyx's addition to the format list and the right default for an analytics
 * handoff (plan section 5.2).
 *
 * <p><b>Why this one is not a pure stream.</b> Parquet is a random-access columnar format: the
 * footer holds the schema and the row-group offsets and can only be written once all row groups are
 * known, and the writer seeks back to patch it. There is no way to emit a valid Parquet file to a
 * forward-only {@code OutputStream}. So this encoder writes to a temp file with a bounded row-group
 * size (memory stays flat and proportional to one row group, not to the export) and copies it to the
 * caller's stream on {@link Writer#close()}. Every other encoder here really is forward-only.
 *
 * <p>Types come from {@link EncoderContext#columnTypes()}; anything unmapped falls back to a
 * nullable UTF-8 string, which is lossless because {@link CellValues#asText} is total.
 */
public final class ParquetEncoder implements Encoder {

  /** 128 MiB row groups - the Parquet default and a good fit for object storage. */
  private static final int DEFAULT_ROW_GROUP_BYTES = 128 * 1024 * 1024;

  @Override
  public String format() {
    return "parquet";
  }

  @Override
  public String contentType() {
    return "application/vnd.apache.parquet";
  }

  @Override
  public String fileExtension() {
    return "parquet";
  }

  @Override
  public Writer open(OutputStream out, EncoderContext context) throws IOException {
    return new ParquetRowWriter(out, context);
  }

  /**
   * Maps a CQL type name onto an Avro type. Visible for testing - this table is the difference
   * between an analytics-ready file and a wall of strings.
   */
  public static Schema avroSchema(List<String> columns, Map<String, String> columnTypes) {
    SchemaBuilder.FieldAssembler<Schema> fields =
        SchemaBuilder.record("cassyx_row").namespace("io.cassyx.bulk").fields();
    for (String column : columns) {
      String cqlType = columnTypes.getOrDefault(column, "text");
      String base = baseType(cqlType);
      switch (base) {
        case "int", "smallint", "tinyint" -> fields.optionalInt(column);
        case "bigint", "counter", "time" -> fields.optionalLong(column);
        case "float" -> fields.optionalFloat(column);
        case "double" -> fields.optionalDouble(column);
        case "boolean" -> fields.optionalBoolean(column);
        case "blob" -> fields.optionalBytes(column);
        default -> fields.optionalString(column);
      }
    }
    return fields.endRecord();
  }

  /** Strips parameters and frozen wrappers: {@code frozen<list<text>>} to {@code list}. */
  static String baseType(String cqlType) {
    if (cqlType == null) {
      return "text";
    }
    String type = cqlType.trim().toLowerCase(Locale.ROOT);
    while (type.startsWith("frozen<") && type.endsWith(">")) {
      type = type.substring("frozen<".length(), type.length() - 1).trim();
    }
    int angle = type.indexOf('<');
    return angle < 0 ? type : type.substring(0, angle);
  }

  private static final class ParquetRowWriter implements Writer {

    private final OutputStream out;
    private final EncoderContext context;
    private final Schema schema;
    private final Path tempFile;
    private final ParquetWriter<GenericRecord> writer;

    ParquetRowWriter(OutputStream out, EncoderContext context) throws IOException {
      this.out = out;
      this.context = context;
      this.schema = avroSchema(context.columns(), context.columnTypes());
      this.tempFile = Files.createTempFile("cassyx-unload-", ".parquet");
      // AvroParquetWriter refuses to overwrite, and createTempFile already made the file.
      Files.deleteIfExists(tempFile);

      Configuration configuration = new Configuration();
      int rowGroupBytes =
          Integer.parseInt(
              context.option("rowGroupSizeBytes", String.valueOf(DEFAULT_ROW_GROUP_BYTES)));
      this.writer =
          AvroParquetWriter.<GenericRecord>builder(
                  HadoopOutputFile.fromPath(
                      new org.apache.hadoop.fs.Path(tempFile.toUri()), configuration))
              .withSchema(schema)
              .withConf(configuration)
              .withCompressionCodec(
                  CompressionCodecName.valueOf(
                      context.option("compression", "SNAPPY").toUpperCase(Locale.ROOT)))
              .withRowGroupSize((long) rowGroupBytes)
              .build();
    }

    @Override
    public void write(Map<String, Object> row) throws IOException {
      GenericRecord record = new GenericData.Record(schema);
      for (String column : context.columns()) {
        Object raw = row.get(column);
        Schema.Field field = schema.getField(column);
        if (raw instanceof java.nio.ByteBuffer buffer) {
          // Blobs go in as real bytes, not as their 0x… rendering.
          record.put(column, buffer.duplicate());
        } else {
          record.put(column, coerce(field, raw));
        }
      }
      writer.write(record);
    }

    private static Object coerce(Schema.Field field, Object raw) {
      Object value = CellValues.normalise(raw);
      if (value == null) {
        return null;
      }
      Schema type = nonNull(field.schema());
      return switch (type.getType()) {
        case INT -> value instanceof Number n ? n.intValue() : Integer.valueOf(value.toString());
        case LONG -> value instanceof Number n ? n.longValue() : Long.valueOf(value.toString());
        case FLOAT -> value instanceof Number n ? n.floatValue() : Float.valueOf(value.toString());
        case DOUBLE -> toDouble(value);
        case BOOLEAN ->
            value instanceof Boolean b ? b : Boolean.valueOf(value.toString());
        case BYTES -> java.nio.ByteBuffer.wrap(value.toString().getBytes(
            java.nio.charset.StandardCharsets.UTF_8));
        default -> CellValues.asText(value);
      };
    }

    private static Object toDouble(Object value) {
      if (value instanceof BigDecimal decimal) {
        return decimal.doubleValue();
      }
      return value instanceof Number n ? n.doubleValue() : Double.valueOf(value.toString());
    }

    /** Unwraps the {@code ["null", T]} union that {@code optionalX()} produces. */
    private static Schema nonNull(Schema schema) {
      if (schema.getType() != Schema.Type.UNION) {
        return schema;
      }
      for (Schema member : schema.getTypes()) {
        if (member.getType() != Schema.Type.NULL) {
          return member;
        }
      }
      return schema;
    }

    @Override
    public void close() throws IOException {
      try {
        writer.close();
        Files.copy(tempFile, out);
        out.flush();
      } finally {
        Files.deleteIfExists(tempFile);
      }
    }
  }
}
