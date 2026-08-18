package io.cassyx.bulk.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.cassyx.bulk.api.BulkFactory;
import io.cassyx.bulk.api.Encoder;
import io.cassyx.bulk.api.Sink;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Proves the ServiceLoader wiring of the Encoder and Sink SPIs actually works. */
class SpiWiringTest {

  @TempDir Path tmp;

  @Test
  void encoderIsDiscoveredByFormat() {
    assertThat(BulkFactory.encoder("CSV")).isInstanceOf(CsvEncoder.class);
    // The full plan section 5.2 format list is registered, so the "unknown format" case now needs
    // a genuinely unregistered one.
    assertThat(BulkFactory.encoders())
        .extracting(Encoder::format)
        .contains("csv", "json", "jsonl", "parquet", "xml", "xlsx");
    assertThatThrownBy(() -> BulkFactory.encoder("avro"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void sinkIsDiscoveredByScheme() {
    assertThat(BulkFactory.sink("file")).isInstanceOf(FileSink.class);
    assertThat(BulkFactory.sinkForTarget("/tmp/out")).isInstanceOf(FileSink.class);
    assertThat(BulkFactory.sinks()).extracting(Sink::scheme).contains("file", "http", "s3");
    assertThatThrownBy(() -> BulkFactory.sink("ftp")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void csvEncoderWritesHeaderAndQuotesCorrectly() throws IOException {
    Encoder encoder = BulkFactory.encoder("csv");
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", 1);
    row.put("name", "a,b\"c");
    row.put("note", null);

    try (Encoder.Writer writer =
        encoder.open(out, Encoder.EncoderContext.of(List.of("id", "name", "note")))) {
      writer.write(row);
    }

    assertThat(out.toString(StandardCharsets.UTF_8))
        .isEqualTo("id,name,note\n1,\"a,b\"\"c\",\n");
    assertThat(encoder.contentType()).isEqualTo("text/csv");
    assertThat(encoder.fileExtension()).isEqualTo("csv");
  }

  @Test
  void fileSinkWritesPartsIntoTheTargetDirectory() throws IOException {
    Sink sink = BulkFactory.sink("file");

    try (OutputStream out = sink.open(tmp.resolve("out").toString(), "part-0001.csv", Map.of())) {
      out.write("x".getBytes(StandardCharsets.UTF_8));
    }

    assertThat(Files.readString(tmp.resolve("out/part-0001.csv"))).isEqualTo("x");
    assertThat(FileSink.toDirectory("file://" + tmp.toAbsolutePath())).isEqualTo(tmp);
    assertThatThrownBy(() -> FileSink.toDirectory(" "))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
