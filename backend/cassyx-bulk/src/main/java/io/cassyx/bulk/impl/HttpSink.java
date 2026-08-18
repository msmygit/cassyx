package io.cassyx.bulk.impl;

import io.cassyx.bulk.api.BulkException;
import io.cassyx.bulk.api.Sink;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.Locale;
import java.util.Map;

/**
 * HTTP streaming sink: PUTs (or POSTs) the encoded output to a URL as it is produced.
 *
 * <p>{@code setChunkedStreamingMode} is the whole point - without it {@code HttpURLConnection}
 * buffers the entire entity in the heap to compute {@code Content-Length}, which would turn a 50M-row
 * unload into an OutOfMemoryError. With it, bytes leave the JVM as they are encoded.
 *
 * <p>Options: {@code method} (default {@code PUT}), {@code contentType}, {@code header.*} for
 * arbitrary request headers, {@code chunkSizeBytes} (default 1 MiB).
 */
public final class HttpSink implements Sink {

  private static final int DEFAULT_CHUNK_BYTES = 1 << 20;

  @Override
  public String scheme() {
    return "http";
  }

  @Override
  public boolean supports(String scheme) {
    return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
  }

  @Override
  public OutputStream open(String target, String partName, Map<String, String> options)
      throws IOException {
    String url = joinPath(target, partName);
    HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
    connection.setDoOutput(true);
    connection.setRequestMethod(
        options.getOrDefault("method", "PUT").toUpperCase(Locale.ROOT));
    connection.setChunkedStreamingMode(
        Integer.parseInt(options.getOrDefault("chunkSizeBytes", String.valueOf(DEFAULT_CHUNK_BYTES))));
    String contentType = options.get("contentType");
    if (contentType != null) {
      connection.setRequestProperty("Content-Type", contentType);
    }
    for (Map.Entry<String, String> option : options.entrySet()) {
      if (option.getKey().startsWith("header.")) {
        connection.setRequestProperty(option.getKey().substring("header.".length()), option.getValue());
      }
    }
    connection.connect();
    return new HttpPartStream(connection.getOutputStream(), connection);
  }

  /** Joins a base URL and a part name without doubling or dropping the separator. */
  public static String joinPath(String target, String partName) {
    if (partName == null || partName.isBlank()) {
      return target;
    }
    return target.endsWith("/") ? target + partName : target + "/" + partName;
  }

  /** Closing the stream finishes the request and fails loudly on a non-2xx response. */
  private static final class HttpPartStream extends FilterOutputStream {

    private final HttpURLConnection connection;
    private boolean closed;

    HttpPartStream(OutputStream delegate, HttpURLConnection connection) {
      super(delegate);
      this.connection = connection;
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      // FilterOutputStream's default writes byte-by-byte; at unload volumes that is fatal.
      out.write(b, off, len);
    }

    @Override
    public void close() throws IOException {
      if (closed) {
        return;
      }
      closed = true;
      super.close();
      int status = connection.getResponseCode();
      try {
        if (status < 200 || status >= 300) {
          throw new BulkException(
              "HTTP sink rejected the upload: " + status + " " + connection.getResponseMessage());
        }
      } finally {
        connection.disconnect();
      }
    }
  }

  /** Visible for testing: URL validity check used before a job is queued. */
  public static URL validate(String target) {
    try {
      return URI.create(target).toURL();
    } catch (IllegalArgumentException | IOException e) {
      throw new BulkException("Invalid HTTP sink target '" + target + "'", e);
    }
  }
}
