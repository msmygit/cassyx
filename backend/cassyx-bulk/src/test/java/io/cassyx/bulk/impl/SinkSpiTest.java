package io.cassyx.bulk.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.cassyx.bulk.api.BulkException;
import io.cassyx.bulk.api.BulkFactory;
import io.cassyx.bulk.api.Sink;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Guards the {@link Sink} SPI: scheme resolution (getting it wrong writes an export to the wrong
 * place, silently), the pure URI/path helpers, and one real end-to-end HTTP upload against a
 * loopback server. No AWS call is made anywhere - only the S3 URI parsing, which is the part that
 * actually misroutes data when it is wrong.
 */
class SinkSpiTest {

  @TempDir Path tmp;

  @Test
  void everyRegisteredSinkIsDiscoverable() {
    assertThat(BulkFactory.sinks())
        .extracting(Sink::scheme)
        .containsExactlyInAnyOrder("file", "http", "s3");
  }

  @Test
  void sinksResolveByScheme() {
    assertThat(BulkFactory.sink("file")).isInstanceOf(FileSink.class);
    assertThat(BulkFactory.sink("http")).isInstanceOf(HttpSink.class);
    // One sink covers both schemes, via the supports() override.
    assertThat(BulkFactory.sink("https")).isInstanceOf(HttpSink.class);
    assertThat(BulkFactory.sink("HTTPS")).isInstanceOf(HttpSink.class);
    assertThat(BulkFactory.sink("s3")).isInstanceOf(S3Sink.class);
  }

  /** A bare path has no scheme at all and must default to the local filesystem, not fail. */
  @Test
  void targetsResolveToTheSinkForTheirScheme() {
    assertThat(BulkFactory.sinkForTarget("/out")).isInstanceOf(FileSink.class);
    assertThat(BulkFactory.sinkForTarget("file:///tmp/x")).isInstanceOf(FileSink.class);
    assertThat(BulkFactory.sinkForTarget("http://host/x")).isInstanceOf(HttpSink.class);
    assertThat(BulkFactory.sinkForTarget("https://host/x")).isInstanceOf(HttpSink.class);
    assertThat(BulkFactory.sinkForTarget("s3://bucket/prefix")).isInstanceOf(S3Sink.class);
  }

  @Test
  void unknownSchemeIsRejected() {
    assertThatThrownBy(() -> BulkFactory.sink("ftp"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ftp");
    assertThatThrownBy(() -> BulkFactory.sinkForTarget("gs://bucket/x"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void fileSinkCreatesMissingDirectoriesAndWritesThePart() throws IOException {
    Sink sink = BulkFactory.sink("file");

    try (OutputStream out =
        sink.open(tmp.resolve("nested/deep").toString(), "part-0001.csv", Map.of())) {
      out.write("hello".getBytes(StandardCharsets.UTF_8));
    }

    assertThat(Files.readString(tmp.resolve("nested/deep/part-0001.csv"))).isEqualTo("hello");
    assertThat(FileSink.toDirectory("file://" + tmp.toAbsolutePath())).isEqualTo(tmp);
    assertThat(FileSink.toDirectory("/a/b")).isEqualTo(Path.of("/a/b"));
    assertThatThrownBy(() -> FileSink.toDirectory(null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> FileSink.toDirectory("  ")).isInstanceOf(IllegalArgumentException.class);
  }

  /** Doubling or dropping the slash silently produces a different URL, so both edges are pinned. */
  @Test
  void httpJoinPathHandlesTheSeparatorEdges() {
    assertThat(HttpSink.joinPath("http://h/base", "p.csv")).isEqualTo("http://h/base/p.csv");
    assertThat(HttpSink.joinPath("http://h/base/", "p.csv")).isEqualTo("http://h/base/p.csv");
    // A blank part name means "the target is already the full URL".
    assertThat(HttpSink.joinPath("http://h/base", null)).isEqualTo("http://h/base");
    assertThat(HttpSink.joinPath("http://h/base", "  ")).isEqualTo("http://h/base");
  }

  /** Pre-flight validation exists so a bad URL fails at submit time, not mid-export. */
  @Test
  void httpValidateAcceptsUrlsAndRejectsGarbage() {
    assertThat(HttpSink.validate("http://localhost:8080/out").getProtocol()).isEqualTo("http");
    assertThat(HttpSink.validate("https://localhost/out").getProtocol()).isEqualTo("https");
    // A space is not a legal URI character, so URI.create rejects it outright.
    assertThatThrownBy(() -> HttpSink.validate("not a url")).isInstanceOf(BulkException.class);
    // A syntactically fine URI with no JDK protocol handler fails on toURL() instead.
    assertThatThrownBy(() -> HttpSink.validate("wat://host/x"))
        .isInstanceOf(BulkException.class)
        .hasMessageContaining("wat://host/x");
  }

  /**
   * The end-to-end proof that the chunked streaming upload works: the bytes handed to the sink's
   * OutputStream must arrive at the server byte-for-byte, under the configured method. Anything
   * that buffers or truncates shows up here.
   */
  @Test
  void httpSinkStreamsBytesToTheServer() throws IOException {
    AtomicReference<byte[]> received = new AtomicReference<>();
    AtomicReference<String> method = new AtomicReference<>();
    AtomicReference<String> path = new AtomicReference<>();
    AtomicReference<String> header = new AtomicReference<>();

    HttpServer server = startServer(exchange -> {
      method.set(exchange.getRequestMethod());
      path.set(exchange.getRequestURI().getPath());
      header.set(exchange.getRequestHeaders().getFirst("X-Cassyx-Job"));
      try (InputStream in = exchange.getRequestBody()) {
        received.set(in.readAllBytes());
      }
      exchange.sendResponseHeaders(204, -1);
      exchange.close();
    });

    byte[] payload = "id,name\n1,ada\n".getBytes(StandardCharsets.UTF_8);
    try {
      Sink sink = BulkFactory.sink("http");
      Map<String, String> options =
          Map.of(
              "method", "post",
              "contentType", "text/csv",
              "header.X-Cassyx-Job", "job-1",
              "chunkSizeBytes", "8");
      try (OutputStream out = sink.open(baseUrl(server) + "/upload", "part-0001.csv", options)) {
        out.write(payload);
      }
    } finally {
      server.stop(0);
    }

    assertThat(received.get()).isEqualTo(payload);
    assertThat(method.get()).isEqualTo("POST");
    assertThat(path.get()).isEqualTo("/upload/part-0001.csv");
    assertThat(header.get()).isEqualTo("job-1");
  }

  /**
   * A server-side failure must surface as an exception on close(), not as a silently truncated
   * export - close() is the only place the response status can be observed.
   */
  @Test
  void httpSinkFailsLoudlyOnANonSuccessResponse() throws IOException {
    HttpServer server = startServer(exchange -> {
      try (InputStream in = exchange.getRequestBody()) {
        in.readAllBytes();
      }
      byte[] body = "boom".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(500, body.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(body);
      }
      exchange.close();
    });

    try {
      Sink sink = BulkFactory.sink("http");
      OutputStream out = sink.open(baseUrl(server) + "/upload", "part.csv", Map.of());
      out.write("x".getBytes(StandardCharsets.UTF_8));
      assertThatThrownBy(out::close)
          .isInstanceOf(BulkException.class)
          .hasMessageContaining("500");
      // close() is idempotent: a second call after the failure must not throw again.
      out.close();
    } finally {
      server.stop(0);
    }
  }

  @Test
  void s3LocationParsesBucketAndKey() {
    assertThat(S3Sink.S3Location.parse("s3://bucket"))
        .isEqualTo(new S3Sink.S3Location("bucket", ""));
    assertThat(S3Sink.S3Location.parse("s3://bucket/"))
        .isEqualTo(new S3Sink.S3Location("bucket", ""));
    assertThat(S3Sink.S3Location.parse("s3://bucket/a/b"))
        .isEqualTo(new S3Sink.S3Location("bucket", "a/b"));
    // A null key normalises to the empty string so callers never see a null bucket-relative key.
    assertThat(new S3Sink.S3Location("bucket", null).key()).isEmpty();
  }

  @Test
  void s3LocationRejectsTargetsThatWouldMisrouteData() {
    assertThatThrownBy(() -> S3Sink.S3Location.parse(null)).isInstanceOf(BulkException.class);
    assertThatThrownBy(() -> S3Sink.S3Location.parse("/local/path"))
        .isInstanceOf(BulkException.class)
        .hasMessageContaining("s3://");
    assertThatThrownBy(() -> S3Sink.S3Location.parse("s3://"))
        .isInstanceOf(BulkException.class)
        .hasMessageContaining("no bucket");
    assertThatThrownBy(() -> S3Sink.S3Location.parse("s3:///key"))
        .isInstanceOf(BulkException.class)
        .hasMessageContaining("no bucket");
  }

  /** The part name must never double the separator: {@code a//part} is a distinct S3 key. */
  @Test
  void s3KeySuffixJoinsWithoutDoublingTheSeparator() {
    S3Sink.S3Location root = S3Sink.S3Location.parse("s3://bucket");
    assertThat(root.withKeySuffix("p.csv").key()).isEqualTo("p.csv");
    assertThat(S3Sink.S3Location.parse("s3://bucket/pre").withKeySuffix("p.csv").key())
        .isEqualTo("pre/p.csv");
    assertThat(S3Sink.S3Location.parse("s3://bucket/pre/").withKeySuffix("p.csv").key())
        .isEqualTo("pre/p.csv");
    // A blank part name leaves the location untouched.
    assertThat(root.withKeySuffix(null)).isSameAs(root);
    assertThat(root.withKeySuffix(" ")).isSameAs(root);
  }

  private static String baseUrl(HttpServer server) {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  private static HttpServer startServer(HttpHandler handler) throws IOException {
    HttpServer server =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext("/", handler);
    server.start();
    return server;
  }
}
