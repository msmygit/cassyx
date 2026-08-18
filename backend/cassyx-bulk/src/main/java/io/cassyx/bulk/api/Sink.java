package io.cassyx.bulk.api;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * SPI for unload destinations: HTTP streaming download, mounted volume path, S3 (plan section 5.2).
 * {@link ServiceLoader}-discovered, keyed by URI scheme.
 */
public interface Sink {

  /** URI scheme this sink handles: {@code file}, {@code http}, {@code s3}. */
  String scheme();

  /**
   * Opens an output stream for one output part.
   *
   * @param target destination URI, e.g. {@code file:///data/out} or {@code s3://bucket/prefix}
   * @param partName file name of this part, e.g. {@code output-000001.csv}
   * @param options sink-specific options (credentials handles, content type, ...)
   */
  OutputStream open(String target, String partName, Map<String, String> options)
      throws IOException;

  static Sink forScheme(String scheme) {
    for (Sink sink : ServiceLoader.load(Sink.class)) {
      if (sink.scheme().equalsIgnoreCase(scheme)) {
        return sink;
      }
    }
    throw new IllegalArgumentException("No Sink registered for scheme '" + scheme + "'");
  }

  /** Picks the sink matching the URI scheme of {@code target}, defaulting to {@code file}. */
  static Sink forTarget(String target) {
    int idx = target == null ? -1 : target.indexOf("://");
    return forScheme(idx < 0 ? "file" : target.substring(0, idx));
  }

  static List<Sink> available() {
    List<Sink> sinks = new java.util.ArrayList<>();
    ServiceLoader.load(Sink.class).forEach(sinks::add);
    return List.copyOf(sinks);
  }
}
