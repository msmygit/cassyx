package io.cassyx.core.impl.astra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cassyx.core.api.astra.AstraDatabase;
import io.cassyx.core.api.astra.AstraDevOpsClient;
import io.cassyx.core.api.astra.AstraDevOpsException;
import io.cassyx.core.api.astra.CustomDomainBundle;
import io.cassyx.core.api.astra.ScbSelector;
import io.cassyx.core.api.astra.SecureBundleEndpoint;
import io.cassyx.core.api.astra.SecureBundleSelection;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link AstraDevOpsClient} over {@code java.net.http.HttpClient}. Plain Java - no Spring.
 *
 * <p><b>The token never reaches a log statement.</b> It is only ever written into the
 * {@code Authorization} header; no log line here interpolates the token, the header map, or a
 * request/response body that could carry it, and exception messages are built from the status code
 * and the request path only. See {@code AstraDevOpsClientTokenLoggingTest}.
 *
 * <p>Phase 1 workstream A owns caching against {@code (databaseId, region, scbType, domain)},
 * encrypted storage of the downloaded bundle and the explicit re-download action (plan section 3.1,
 * deviations 4 and 5). The API contract and URL resolution implemented here are final.
 */
public final class HttpAstraDevOpsClient implements AstraDevOpsClient {

  private static final Logger LOG = LoggerFactory.getLogger(HttpAstraDevOpsClient.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final HttpClient httpClient;
  private final String baseUrl;
  private final String token;
  private final Duration requestTimeout;

  public HttpAstraDevOpsClient(String token) {
    this(token, DEFAULT_BASE_URL, defaultHttpClient(), Duration.ofSeconds(30));
  }

  public HttpAstraDevOpsClient(String token, String baseUrl) {
    this(token, baseUrl, defaultHttpClient(), Duration.ofSeconds(30));
  }

  public HttpAstraDevOpsClient(
      String token, String baseUrl, HttpClient httpClient, Duration requestTimeout) {
    this.token = Objects.requireNonNull(token, "token");
    this.baseUrl = stripTrailingSlash(Objects.requireNonNull(baseUrl, "baseUrl"));
    this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    this.requestTimeout = requestTimeout;
  }

  private static HttpClient defaultHttpClient() {
    return HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();
  }

  private static String stripTrailingSlash(String url) {
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }

  @Override
  public List<AstraDatabase> listDatabases() {
    String path = "/v2/databases";
    JsonNode json = parse(send(get(path), path), path);
    List<AstraDatabase> databases = new ArrayList<>();
    for (JsonNode node : json) {
      JsonNode info = node.path("info");
      List<String> regions = new ArrayList<>();
      for (JsonNode region : info.path("datacenters").isMissingNode()
          ? node.path("info").path("regions")
          : info.path("datacenters")) {
        String value = region.isTextual() ? region.asText() : region.path("region").asText(null);
        if (value != null && !value.isBlank()) {
          regions.add(value);
        }
      }
      databases.add(
          new AstraDatabase(
              node.path("id").asText(null),
              info.path("name").asText(null),
              node.path("status").asText(null),
              regions));
    }
    return List.copyOf(databases);
  }

  @Override
  public List<SecureBundleEndpoint> secureBundleEndpoints(String databaseId) {
    requireDatabaseId(databaseId);
    String path = "/v2/databases/" + databaseId + "/secureBundleURL?all=true";
    JsonNode json = parse(send(postNoBody(path), path), path);
    if (!json.isArray()) {
      throw new AstraDevOpsException(
          "Unexpected Astra DevOps response for " + redactPath(path) + ": expected a JSON array");
    }
    List<SecureBundleEndpoint> endpoints = new ArrayList<>();
    for (JsonNode node : json) {
      List<CustomDomainBundle> custom = new ArrayList<>();
      for (JsonNode bundle : node.path("customDomainBundles")) {
        custom.add(
            new CustomDomainBundle(
                bundle.path("domain").asText(null), bundle.path("downloadURL").asText(null)));
      }
      endpoints.add(
          new SecureBundleEndpoint(
              node.path("region").asText(null), node.path("downloadURL").asText(null), custom));
    }
    return List.copyOf(endpoints);
  }

  @Override
  public String resolveBundleUrl(String databaseId, ScbSelector selector) {
    Objects.requireNonNull(selector, "selector");
    return SecureBundleSelection.selectDownloadUrl(secureBundleEndpoints(databaseId), selector);
  }

  @Override
  public Path downloadBundle(String databaseId, ScbSelector selector, Path target) {
    String url = resolveBundleUrl(databaseId, selector);
    // Pre-signed URL: deliberately NO Authorization header on this request.
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(url)).timeout(requestTimeout).GET().build();
    try {
      HttpResponse<InputStream> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
      if (response.statusCode() / 100 != 2) {
        throw new AstraDevOpsException(
            "Secure connect bundle download failed with HTTP " + response.statusCode(),
            response.statusCode(),
            null);
      }
      try (InputStream body = response.body()) {
        Files.createDirectories(target.toAbsolutePath().getParent());
        Files.copy(body, target, StandardCopyOption.REPLACE_EXISTING);
      }
      LOG.info("Downloaded secure connect bundle for database {} to {}", databaseId, target);
      return target;
    } catch (IOException e) {
      throw new AstraDevOpsException(
          "Secure connect bundle download failed: " + e.getClass().getSimpleName(), 0, null);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AstraDevOpsException("Secure connect bundle download was interrupted");
    }
  }

  private static void requireDatabaseId(String databaseId) {
    if (databaseId == null || databaseId.isBlank()) {
      throw new AstraDevOpsException("databaseId is required");
    }
  }

  private HttpRequest get(String path) {
    return authorized(HttpRequest.newBuilder(URI.create(baseUrl + path))).GET().build();
  }

  private HttpRequest postNoBody(String path) {
    return authorized(HttpRequest.newBuilder(URI.create(baseUrl + path)))
        .POST(HttpRequest.BodyPublishers.noBody())
        .build();
  }

  private HttpRequest.Builder authorized(HttpRequest.Builder builder) {
    // The ONLY place the token is used. Never logged, never echoed into an exception.
    return builder
        .header("Authorization", "Bearer " + token)
        .header("Content-Type", "application/json")
        .header("Accept", "application/json")
        .timeout(requestTimeout);
  }

  private String send(HttpRequest request, String path) {
    try {
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      int status = response.statusCode();
      if (status / 100 != 2) {
        // Log the status and path only. Never the headers and never the body: an Astra error body
        // can echo the request, and this is exactly where tokens leak in practice.
        LOG.warn("Astra DevOps API returned HTTP {} for {}", status, redactPath(path));
        throw new AstraDevOpsException(describeStatus(status, path), status, null);
      }
      return response.body();
    } catch (IOException e) {
      // Transport failure. Log the exception TYPE only - never the message, which can echo the
      // request, and never the exception itself, whose stack trace can carry header state.
      LOG.warn(
          "Astra DevOps API request to {} failed: {}", redactPath(path), e.getClass().getName());
      throw new AstraDevOpsException(
          "Cannot reach the Astra DevOps API at "
              + baseUrl
              + " ("
              + e.getClass().getSimpleName()
              + "). If this deployment has no outbound internet access, switch the secure connect "
              + "bundle acquisition mode to UPLOAD or PATH. Egress-free installs are common "
              + "(plan section 9.1), so this is not retried silently.",
          0,
          null);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AstraDevOpsException("Astra DevOps API request was interrupted");
    }
  }

  private static String describeStatus(int status, String path) {
    return switch (status) {
      case 401, 403 ->
          "Astra rejected the token (HTTP " + status + "). Check that the token is an "
              + "'AstraCS:...' token with database access.";
      case 404 -> "Astra resource not found (HTTP 404) for " + redactPath(path);
      case 429 -> "Astra DevOps API rate limit reached (HTTP 429); retry shortly.";
      default -> "Astra DevOps API call failed with HTTP " + status + " for " + redactPath(path);
    };
  }

  /** Paths carry only database ids and query flags, but strip the query string defensively. */
  private static String redactPath(String path) {
    int q = path.indexOf('?');
    return q < 0 ? path : path.substring(0, q);
  }

  private static JsonNode parse(String body, String path) {
    try {
      return MAPPER.readTree(body == null ? "" : body);
    } catch (IOException e) {
      throw new AstraDevOpsException(
          "Could not parse the Astra DevOps response for " + redactPath(path), 0, null);
    }
  }
}
