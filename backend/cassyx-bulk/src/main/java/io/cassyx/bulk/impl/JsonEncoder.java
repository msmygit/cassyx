package io.cassyx.bulk.impl;

import com.fasterxml.jackson.core.JsonGenerator;
import io.cassyx.bulk.api.Encoder;
import java.io.IOException;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JSON array encoder: {@code [{...},{...}]}.
 *
 * <p>Streaming by construction - the Jackson generator writes each row straight through and the
 * enclosing array is opened once and closed once, so a 50M-row unload never materialises a list.
 */
public final class JsonEncoder implements Encoder {

  @Override
  public String format() {
    return "json";
  }

  @Override
  public String contentType() {
    return "application/json";
  }

  @Override
  public String fileExtension() {
    return "json";
  }

  @Override
  public Writer open(OutputStream out, EncoderContext context) throws IOException {
    return new JsonWriter(out, context, true);
  }

  /** Shared with {@link JsonLinesEncoder}; {@code array} false emits one object per line. */
  static final class JsonWriter implements Writer {

    private final JsonGenerator generator;
    private final EncoderContext context;
    private final boolean array;
    private final boolean prettyPrint;
    private long rows;

    JsonWriter(OutputStream out, EncoderContext context, boolean array) throws IOException {
      this.generator = Json.generator(out);
      this.context = context;
      this.array = array;
      this.prettyPrint = Boolean.parseBoolean(context.option("pretty", "false"));
      if (prettyPrint) {
        generator.useDefaultPrettyPrinter();
      }
      if (array) {
        generator.writeStartArray();
      } else {
        // JSON Lines: one self-delimited document per line, never an array.
        generator.setRootValueSeparator(
            new com.fasterxml.jackson.core.io.SerializedString("\n"));
      }
    }

    @Override
    public void write(Map<String, Object> row) throws IOException {
      Map<String, Object> ordered = new LinkedHashMap<>();
      for (String column : context.columns()) {
        ordered.put(column, CellValues.normalise(row.get(column)));
      }
      Json.mapper().writeValue(generator, ordered);
      rows++;
    }

    @Override
    public void close() throws IOException {
      if (array) {
        generator.writeEndArray();
      } else if (rows > 0) {
        generator.writeRaw('\n');
      }
      generator.flush();
      generator.close();
    }
  }
}
