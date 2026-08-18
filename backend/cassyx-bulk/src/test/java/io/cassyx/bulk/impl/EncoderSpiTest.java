package io.cassyx.bulk.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cassyx.bulk.api.BulkFactory;
import io.cassyx.bulk.api.Encoder;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Guards the {@link Encoder} SPI contract: every format advertised in {@code META-INF/services} must
 * actually be discoverable, and the two text-streaming formats (json / jsonl) must produce output a
 * downstream tool can parse. A missing service entry is invisible at compile time and only shows up
 * as a runtime "No Encoder registered", so discovery is asserted per format rather than in bulk.
 */
class EncoderSpiTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final List<String> COLUMNS = List.of("id", "name", "note");

  @Test
  void everyRegisteredFormatIsDiscoverable() {
    assertThat(BulkFactory.encoders())
        .extracting(Encoder::format)
        .containsExactlyInAnyOrder("csv", "json", "jsonl", "parquet", "xml", "xlsx");
  }

  @Test
  void encodersResolveToTheirImplementationByFormatId() {
    assertThat(BulkFactory.encoder("csv")).isInstanceOf(CsvEncoder.class);
    assertThat(BulkFactory.encoder("json")).isInstanceOf(JsonEncoder.class);
    assertThat(BulkFactory.encoder("jsonl")).isInstanceOf(JsonLinesEncoder.class);
    assertThat(BulkFactory.encoder("parquet")).isInstanceOf(ParquetEncoder.class);
    assertThat(BulkFactory.encoder("xml")).isInstanceOf(XmlEncoder.class);
    assertThat(BulkFactory.encoder("xlsx")).isInstanceOf(XlsxEncoder.class);
  }

  /** Lookup is case-insensitive so a user-supplied "CSV" from the UI does not 400. */
  @Test
  void formatLookupIgnoresCase() {
    assertThat(BulkFactory.encoder("JSONL").format()).isEqualTo("jsonl");
    assertThat(BulkFactory.encoder("XlSx").format()).isEqualTo("xlsx");
  }

  @Test
  void unknownFormatIsRejected() {
    assertThatThrownBy(() -> BulkFactory.encoder("avro"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("avro");
  }

  /**
   * The content type is what the browser sees on a streaming download and the extension is what the
   * part files are named, so both are part of the public contract, not cosmetics.
   */
  @Test
  void contentTypesAndExtensionsAreStable() {
    assertThat(BulkFactory.encoder("csv").contentType()).isEqualTo("text/csv");
    assertThat(BulkFactory.encoder("csv").fileExtension()).isEqualTo("csv");
    assertThat(BulkFactory.encoder("json").contentType()).isEqualTo("application/json");
    assertThat(BulkFactory.encoder("json").fileExtension()).isEqualTo("json");
    assertThat(BulkFactory.encoder("jsonl").contentType()).isEqualTo("application/x-ndjson");
    assertThat(BulkFactory.encoder("jsonl").fileExtension()).isEqualTo("jsonl");
    assertThat(BulkFactory.encoder("parquet").contentType()).isEqualTo("application/vnd.apache.parquet");
    assertThat(BulkFactory.encoder("parquet").fileExtension()).isEqualTo("parquet");
    assertThat(BulkFactory.encoder("xml").contentType()).isEqualTo("application/xml");
    assertThat(BulkFactory.encoder("xml").fileExtension()).isEqualTo("xml");
    assertThat(BulkFactory.encoder("xlsx").contentType())
        .isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    assertThat(BulkFactory.encoder("xlsx").fileExtension()).isEqualTo("xlsx");
  }

  /** The context defaults exist so callers can pass only a projection and still get sane output. */
  @Test
  void encoderContextNormalisesNullsAndDefaults() {
    Encoder.EncoderContext context = new Encoder.EncoderContext(null, null, null);
    assertThat(context.columns()).isEmpty();
    assertThat(context.columnTypes()).isEmpty();
    assertThat(context.options()).isEmpty();
    assertThat(context.option("header", "true")).isEqualTo("true");
    // Unknown columns are text: the safe, lossless fallback for the typed formats.
    assertThat(context.columnType("nope")).isEqualTo("text");

    Encoder.EncoderContext withOptions =
        Encoder.EncoderContext.of(List.of("a"), Map.of("header", "false"));
    assertThat(withOptions.option("header", "true")).isEqualTo("false");
    assertThat(withOptions.columnTypes()).isEmpty();

    Encoder.EncoderContext typed =
        new Encoder.EncoderContext(List.of("a"), Map.of("a", "bigint"), Map.of());
    assertThat(typed.columnType("a")).isEqualTo("bigint");
  }

  /**
   * A JSON export must be a single valid document, not a concatenation of objects - that is the
   * whole difference between the {@code json} and {@code jsonl} formats.
   */
  @Test
  void jsonEncoderProducesOneParsableArray() throws IOException {
    byte[] bytes = encode("json", Map.of(), rows());

    JsonNode root = MAPPER.readTree(bytes);
    assertThat(root.isArray()).isTrue();
    assertThat(root.size()).isEqualTo(2);
    assertThat(root.get(0).get("id").asInt()).isEqualTo(1);
    assertThat(root.get(0).get("name").asText()).isEqualTo("ada");
    // Explicit null in the row map.
    assertThat(root.get(0).get("note").isNull()).isTrue();
    // Column present in the projection but absent from the row map: still emitted, as null.
    assertThat(root.get(1).has("note")).isTrue();
    assertThat(root.get(1).get("note").isNull()).isTrue();
  }

  /** Zero rows still has to be valid JSON, not an empty file. */
  @Test
  void jsonEncoderWithNoRowsIsAnEmptyArray() throws IOException {
    byte[] bytes = encode("json", Map.of(), List.of());
    assertThat(new String(bytes, StandardCharsets.UTF_8)).isEqualTo("[]");
  }

  /** The pretty option must not change the parsed document, only its whitespace. */
  @Test
  void jsonEncoderHonoursThePrettyOption() throws IOException {
    byte[] bytes = encode("json", Map.of("pretty", "true"), rows());
    assertThat(new String(bytes, StandardCharsets.UTF_8)).contains("\n");
    assertThat(MAPPER.readTree(bytes).size()).isEqualTo(2);
  }

  /**
   * NDJSON consumers split on newlines, so N rows must be exactly N lines with no trailing blank
   * one - a stray final newline yields a spurious empty record in most readers.
   */
  @Test
  void jsonLinesEmitsOneDocumentPerLine() throws IOException {
    String text = new String(encode("jsonl", Map.of(), rows()), StandardCharsets.UTF_8);

    assertThat(text).endsWith("\n").doesNotContain("\n\n");
    String[] lines = text.split("\n", -1);
    // Trailing "" after the final newline; the real records are the first two entries.
    assertThat(lines).hasSize(3);
    assertThat(lines[2]).isEmpty();
    assertThat(MAPPER.readTree(lines[0]).get("name").asText()).isEqualTo("ada");
    assertThat(MAPPER.readTree(lines[1]).get("name").isNull()).isTrue();
    assertThat(MAPPER.readTree(lines[1]).get("note").isNull()).isTrue();
  }

  /** No rows must mean no bytes at all: a lone newline would decode as an empty record. */
  @Test
  void jsonLinesWithNoRowsIsEmpty() throws IOException {
    assertThat(encode("jsonl", Map.of(), List.of())).isEmpty();
  }

  private static List<Map<String, Object>> rows() {
    Map<String, Object> first = new LinkedHashMap<>();
    first.put("id", 1);
    first.put("name", "ada");
    first.put("note", null);

    // "note" is deliberately absent, and "name" is explicitly null.
    Map<String, Object> second = new HashMap<>();
    second.put("id", 2);
    second.put("name", null);

    return List.of(first, second);
  }

  private static byte[] encode(String format, Map<String, String> options, List<Map<String, Object>> rows)
      throws IOException {
    Encoder encoder = BulkFactory.encoder(format);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (Encoder.Writer writer = encoder.open(out, Encoder.EncoderContext.of(COLUMNS, options))) {
      for (Map<String, Object> row : rows) {
        writer.write(row);
      }
    }
    return out.toByteArray();
  }
}
