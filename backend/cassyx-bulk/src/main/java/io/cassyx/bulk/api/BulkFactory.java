package io.cassyx.bulk.api;

import java.util.List;

/**
 * Composition entry point of cassyx-bulk - the only class outside {@code io.cassyx.bulk.impl} that
 * knows the implementations exist (plan section 2.1).
 *
 * <pre>{@code
 * Encoder csv = BulkFactory.encoder("csv");
 * try (Encoder.Writer writer =
 *          csv.open(Files.newOutputStream(Path.of("out.csv")),
 *                   Encoder.EncoderContext.of(List.of("id", "name")))) {
 *   writer.write(Map.of("id", 1, "name", "ada"));
 * }
 * }</pre>
 */
public final class BulkFactory {

  private BulkFactory() {}

  /** ServiceLoader lookup by format id: {@code csv}, and whatever else is on the classpath. */
  public static Encoder encoder(String format) {
    return Encoder.forFormat(format);
  }

  public static List<Encoder> encoders() {
    return Encoder.available();
  }

  /** ServiceLoader lookup by URI scheme: {@code file}, {@code http}, {@code s3}. */
  public static Sink sink(String scheme) {
    return Sink.forScheme(scheme);
  }

  public static Sink sinkForTarget(String target) {
    return Sink.forTarget(target);
  }

  public static List<Sink> sinks() {
    return Sink.available();
  }
}
