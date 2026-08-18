package io.cassyx.bulk.impl;

import io.cassyx.bulk.api.Sink;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * Reference {@link Sink}: writes parts to a mounted volume path. Never buffers - the stream goes
 * straight to disk, which is the whole point of plan section 5.2 ("bulk data must never round-trip
 * through the browser").
 */
public final class FileSink implements Sink {

  @Override
  public String scheme() {
    return "file";
  }

  @Override
  public OutputStream open(String target, String partName, Map<String, String> options)
      throws IOException {
    Path directory = toDirectory(target);
    Files.createDirectories(directory);
    return Files.newOutputStream(directory.resolve(partName));
  }

  /** Visible for testing: accepts both {@code file:///a/b} and a plain {@code /a/b}. */
  public static Path toDirectory(String target) {
    if (target == null || target.isBlank()) {
      throw new IllegalArgumentException("File sink target is empty");
    }
    if (target.startsWith("file:")) {
      return Paths.get(URI.create(target));
    }
    return Paths.get(target);
  }
}
