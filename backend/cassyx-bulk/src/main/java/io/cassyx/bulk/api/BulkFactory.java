package io.cassyx.bulk.api;

import io.cassyx.bulk.impl.EvenTokenRangeSplitter;
import io.cassyx.bulk.impl.TokenRangeCountEngine;
import io.cassyx.bulk.impl.TokenRangeUnloadEngine;
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

  /**
   * The token-range parallel unload engine of plan section 5.2.
   *
   * <pre>{@code
   * UnloadResult result = BulkFactory.unloadEngine()
   *     .unload(session,
   *             UnloadRequest.of("demo", "users", "csv", "/out"),
   *             progress -> System.out.println(progress.fraction()));
   * }</pre>
   *
   * <p>A {@link com.datastax.oss.driver.api.core.CqlSession} is the only thing it needs - no Spring,
   * no session registry, no web layer (plan section 2.1).
   */
  public static UnloadEngine unloadEngine() {
    return new TokenRangeUnloadEngine();
  }

  /** Native count / statistics over the same token-range plan (plan section 5.4). */
  public static CountEngine countEngine() {
    return new TokenRangeCountEngine();
  }

  /**
   * Native count for a target that cannot do {@code token()} range scans (plan section 7.1).
   *
   * <p>This is the only reason the native count engine is reachable from outside the module: on
   * Amazon Keyspaces the DSBulk {@code count} workflow has no usable plan, so a {@code global}
   * count degrades to one plain aggregate rather than being refused. Every other statistics mode is
   * genuinely unavailable there and is rejected rather than quietly downgraded.
   */
  public static CountEngine pagingCountEngine() {
    return new TokenRangeCountEngine(tokenRangeSplitter(), 10, false);
  }

  /** The oversplit + {@code unwrap()} splitter, exposed for pre-flight estimates and tests. */
  public static TokenRangeSplitter tokenRangeSplitter() {
    return new EvenTokenRangeSplitter();
  }
}
