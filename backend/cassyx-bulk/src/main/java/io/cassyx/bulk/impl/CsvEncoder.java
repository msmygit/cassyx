package io.cassyx.bulk.impl;

import io.cassyx.bulk.api.Encoder;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Reference {@link Encoder}: RFC 4180 CSV. Streaming - one row at a time, never buffered.
 *
 * <p>Options: {@code delimiter} (default {@code ,}), {@code header} (default {@code true}),
 * {@code nullString} (default empty).
 */
public final class CsvEncoder implements Encoder {

  @Override
  public String format() {
    return "csv";
  }

  @Override
  public String contentType() {
    return "text/csv";
  }

  @Override
  public String fileExtension() {
    return "csv";
  }

  @Override
  public Writer open(OutputStream out, EncoderContext context) throws IOException {
    return new CsvWriter(out, context);
  }

  private static final class CsvWriter implements Writer {

    private final BufferedWriter out;
    private final EncoderContext context;
    private final char delimiter;
    private final String nullString;

    CsvWriter(OutputStream out, EncoderContext context) throws IOException {
      this.out = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8), 1 << 16);
      this.context = context;
      this.delimiter = context.option("delimiter", ",").charAt(0);
      this.nullString = context.option("nullString", "");
      if (Boolean.parseBoolean(context.option("header", "true"))) {
        writeRecord(context.columns().toArray(new String[0]));
      }
    }

    @Override
    public void write(Map<String, Object> row) throws IOException {
      String[] values = new String[context.columns().size()];
      for (int i = 0; i < values.length; i++) {
        Object value = row.get(context.columns().get(i));
        values[i] = value == null ? nullString : String.valueOf(value);
      }
      writeRecord(values);
    }

    private void writeRecord(String[] values) throws IOException {
      for (int i = 0; i < values.length; i++) {
        if (i > 0) {
          out.write(delimiter);
        }
        out.write(quote(values[i], delimiter));
      }
      out.write('\n');
    }

    @Override
    public void close() throws IOException {
      out.flush();
      out.close();
    }
  }

  /** Visible for testing: RFC 4180 quoting. */
  public static String quote(String value, char delimiter) {
    if (value == null) {
      return "";
    }
    boolean needsQuote =
        value.indexOf(delimiter) >= 0
            || value.indexOf('"') >= 0
            || value.indexOf('\n') >= 0
            || value.indexOf('\r') >= 0;
    if (!needsQuote) {
      return value;
    }
    return '"' + value.replace("\"", "\"\"") + '"';
  }
}
