package io.cassyx.core.impl.astra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.cassyx.core.api.astra.AstraDatabase;
import io.cassyx.core.api.astra.AstraDevOpsClient;
import io.cassyx.core.api.astra.AstraDevOpsException;
import io.cassyx.core.api.astra.ScbSelector;
import io.cassyx.core.api.astra.SecureBundleEndpoint;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Exercises the DevOps API contract of plan section 3.1 against a local stub server. */
class HttpAstraDevOpsClientTest {

  private HttpServer server;
  private String baseUrl;
  private final AtomicReference<String> lastAuthHeader = new AtomicReference<>();

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  private void respond(String path, int status, String body) {
    server.createContext(
        path,
        exchange -> {
          lastAuthHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
          write(exchange, status, body);
        });
    server.start();
  }

  private static void write(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  @Test
  void listsDatabasesForThePicker() {
    respond(
        "/v2/databases",
        200,
        """
        [ { "id": "db-1", "status": "ACTIVE",
            "info": { "name": "prod", "datacenters": [ { "region": "us-east1" } ] } } ]
        """);

    List<AstraDatabase> databases =
        new HttpAstraDevOpsClient("AstraCS:sentinel", baseUrl).listDatabases();

    assertThat(databases).hasSize(1);
    assertThat(databases.get(0).id()).isEqualTo("db-1");
    assertThat(databases.get(0).name()).isEqualTo("prod");
    assertThat(databases.get(0).regions()).containsExactly("us-east1");
    assertThat(lastAuthHeader.get()).isEqualTo("Bearer AstraCS:sentinel");
  }

  @Test
  void parsesSecureBundleArrayAndResolvesUrl() {
    respond(
        "/v2/databases/db-1/secureBundleURL",
        200,
        """
        [ { "region": "us-east1", "downloadURL": "https://x.invalid/a.zip",
            "customDomainBundles": [ { "domain": "d.example.com",
                                       "downloadURL": "https://x.invalid/b.zip" } ] },
          { "region": "eu-west1", "downloadURL": "https://x.invalid/c.zip" } ]
        """);
    AstraDevOpsClient client = new HttpAstraDevOpsClient("AstraCS:sentinel", baseUrl);

    List<SecureBundleEndpoint> endpoints = client.secureBundleEndpoints("db-1");

    assertThat(endpoints).hasSize(2);
    assertThat(client.resolveBundleUrl("db-1", ScbSelector.defaultBundleIn("eu-west1")))
        .isEqualTo("https://x.invalid/c.zip");
    assertThat(
            client.resolveBundleUrl(
                "db-1", ScbSelector.customDomain("us-east1", "d.example.com")))
        .isEqualTo("https://x.invalid/b.zip");
  }

  @Test
  void requiresDatabaseId() {
    AstraDevOpsClient client = new HttpAstraDevOpsClient("AstraCS:sentinel", baseUrl);

    assertThatThrownBy(() -> client.secureBundleEndpoints(" "))
        .isInstanceOf(AstraDevOpsException.class);
  }

  @Test
  void unauthorizedIsReportedActionably() {
    respond("/v2/databases", 401, "{\"errors\":[{\"message\":\"bad token\"}]}");

    assertThatThrownBy(() -> new HttpAstraDevOpsClient("AstraCS:sentinel", baseUrl).listDatabases())
        .isInstanceOf(AstraDevOpsException.class)
        .hasMessageContaining("AstraCS:...")
        .satisfies(e -> assertThat(((AstraDevOpsException) e).statusCode()).isEqualTo(401));
  }

  @Test
  void unreachableApiPointsAtManualUpload() {
    // Port 1 on loopback: nothing can be listening, so this is a hard connection failure.
    assertThatThrownBy(
            () -> new HttpAstraDevOpsClient("AstraCS:sentinel", "http://127.0.0.1:1").listDatabases())
        .isInstanceOf(AstraDevOpsException.class)
        .hasMessageContaining("UPLOAD");
  }
}
