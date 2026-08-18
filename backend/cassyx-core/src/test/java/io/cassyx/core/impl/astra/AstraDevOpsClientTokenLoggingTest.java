package io.cassyx.core.impl.astra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/**
 * REQUIRED by plan section 3.1 ("Security"): the Astra token must never appear in log output,
 * <b>especially</b> on error paths - that is exactly where tokens leak in practice.
 *
 * <p>Every DevOps call is driven through a failure and the captured log output (plus the resulting
 * exception chain) is asserted not to contain the sentinel token.
 */
class AstraDevOpsClientTokenLoggingTest {

  private static final String SENTINEL_TOKEN =
      "AstraCS:SENTINELdoNotLogMe:0123456789abcdef0123456789abcdef";

  @TempDir Path tmp;

  private HttpServer server;
  private String baseUrl;
  private ListAppender<ILoggingEvent> appender;
  private ch.qos.logback.classic.Logger rootLogger;

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

    LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
    rootLogger = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    rootLogger.setLevel(Level.TRACE);
    appender = new ListAppender<>();
    appender.setContext(context);
    appender.start();
    rootLogger.addAppender(appender);
  }

  @AfterEach
  void tearDown() {
    rootLogger.detachAppender(appender);
    appender.stop();
    if (server != null) {
      server.stop(0);
    }
  }

  private void stub(String path, int status, String body) {
    server.createContext(
        path,
        exchange -> {
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status, bytes.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
          }
        });
    server.start();
  }

  private String capturedLogs() {
    return appender.list.stream()
        .map(
            event ->
                event.getFormattedMessage()
                    + " "
                    + (event.getThrowableProxy() == null
                        ? ""
                        : event.getThrowableProxy().getMessage())
                    + " "
                    + java.util.Arrays.toString(event.getArgumentArray()))
        .collect(Collectors.joining("\n"));
  }

  private static String chainText(Throwable t) {
    StringBuilder sb = new StringBuilder();
    for (Throwable current = t; current != null; current = current.getCause()) {
      sb.append(current).append('\n');
    }
    return sb.toString();
  }

  @Test
  void tokenIsNeverLoggedOnHttpErrorResponses() {
    // 401 bodies from Astra can echo the request - the worst case for leakage.
    stub(
        "/v2/databases",
        401,
        "{\"errors\":[{\"message\":\"invalid token " + SENTINEL_TOKEN + "\"}]}");

    Throwable thrown =
        catchThrowable(() -> new HttpAstraDevOpsClient(SENTINEL_TOKEN, baseUrl).listDatabases());

    assertThat(thrown).isNotNull();
    assertThat(capturedLogs()).doesNotContain(SENTINEL_TOKEN).contains("401");
    assertThat(chainText(thrown)).doesNotContain(SENTINEL_TOKEN);
  }

  @Test
  void tokenIsNeverLoggedOnMalformedResponses() {
    stub("/v2/databases/db-1/secureBundleURL", 200, "{ not json ");

    Throwable thrown =
        catchThrowable(
            () ->
                new HttpAstraDevOpsClient(SENTINEL_TOKEN, baseUrl)
                    .secureBundleEndpoints("db-1"));

    assertThat(thrown).isNotNull();
    assertThat(capturedLogs()).doesNotContain(SENTINEL_TOKEN);
    assertThat(chainText(thrown)).doesNotContain(SENTINEL_TOKEN);
  }

  @Test
  void tokenIsNeverLoggedWhenTheApiIsUnreachable() {
    // Port 1 on loopback: nothing can be listening, so this exercises the transport-failure branch.
    Throwable thrown =
        catchThrowable(
            () -> new HttpAstraDevOpsClient(SENTINEL_TOKEN, "http://127.0.0.1:1").listDatabases());

    assertThat(thrown).isNotNull();
    assertThat(capturedLogs()).doesNotContain(SENTINEL_TOKEN);
    assertThat(chainText(thrown)).doesNotContain(SENTINEL_TOKEN);
  }

  @Test
  void tokenIsNeverLoggedOnBundleDownloadFailure() {
    stub(
        "/v2/databases/db-1/secureBundleURL",
        200,
        "[ { \"region\": \"us-east1\", \"downloadURL\": \"http://127.0.0.1:1/bundle.zip\" } ]");

    Throwable thrown =
        catchThrowable(
            () ->
                new HttpAstraDevOpsClient(SENTINEL_TOKEN, baseUrl)
                    .downloadBundle(
                        "db-1",
                        io.cassyx.core.api.astra.ScbSelector.defaultBundle(),
                        tmp.resolve("bundle.zip")));

    assertThat(thrown).isNotNull();
    assertThat(capturedLogs()).doesNotContain(SENTINEL_TOKEN);
    assertThat(chainText(thrown)).doesNotContain(SENTINEL_TOKEN);
  }

  @Test
  void connectionSpecToStringRedactsCredentials() {
    var spec =
        io.cassyx.core.api.ConnectionSpec.astra(
            "astra",
            new io.cassyx.core.api.AstraConnection(
                io.cassyx.core.api.Secret.of(SENTINEL_TOKEN),
                "db-1",
                io.cassyx.core.api.ScbAcquisitionMode.AUTO_DOWNLOAD,
                null,
                null,
                null));

    assertThat(spec.toString()).doesNotContain(SENTINEL_TOKEN).contains("<redacted>");
  }
}
