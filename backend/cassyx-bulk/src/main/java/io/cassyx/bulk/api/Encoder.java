package io.cassyx.bulk.api;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * SPI for output formats: CSV, JSON/JSONL, Parquet, XML, Excel (plan section 5.2).
 *
 * <p>Discovered with {@link ServiceLoader}. Adding Parquet means adding one class plus a
 * {@code META-INF/services} entry - never an if/else chain (plan section 2.1).
 *
 * <p>Encoders are streaming by contract: {@link #write} may be called millions of times and must
 * never buffer the whole result set.
 */
public interface Encoder {

  /** Stable format id, e.g. {@code csv}, {@code json}, {@code parquet}. */
  String format();

  /** MIME type for HTTP streaming downloads. */
  String contentType();

  /** Conventional file extension, without the dot. */
  String fileExtension();

  /** Opens a streaming writer over {@code out}. The caller closes the returned writer. */
  Writer open(OutputStream out, EncoderContext context) throws IOException;

  /** Per-job streaming writer. Not thread-safe: one writer per output stream. */
  interface Writer extends AutoCloseable {

    void write(Map<String, Object> row) throws IOException;

    @Override
    void close() throws IOException;
  }

  /**
   * Everything an encoder needs that is not the row itself.
   *
   * @param columns projection order; encoders must honour it
   * @param options format-specific options (delimiter, header, compression, ...)
   */
  record EncoderContext(List<String> columns, Map<String, String> options) {

    public EncoderContext {
      columns = columns == null ? List.of() : List.copyOf(columns);
      options = options == null ? Map.of() : Map.copyOf(options);
    }

    public static EncoderContext of(List<String> columns) {
      return new EncoderContext(columns, Map.of());
    }

    public String option(String key, String defaultValue) {
      return options.getOrDefault(key, defaultValue);
    }
  }

  /** Loads the encoder registered for {@code format}. */
  static Encoder forFormat(String format) {
    for (Encoder encoder : ServiceLoader.load(Encoder.class)) {
      if (encoder.format().equalsIgnoreCase(format)) {
        return encoder;
      }
    }
    throw new IllegalArgumentException("No Encoder registered for format '" + format + "'");
  }

  static List<Encoder> available() {
    List<Encoder> encoders = new java.util.ArrayList<>();
    ServiceLoader.load(Encoder.class).forEach(encoders::add);
    return List.copyOf(encoders);
  }
}
