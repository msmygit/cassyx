package io.cassyx.api.billing;

import io.cassyx.license.api.PaymentProvider;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Forwards fulfilment to the operator-run {@code licensing/} service (plan sections 9.1, 9.3).
 *
 * <p>When no licensing URL is configured the fulfilment is logged at ERROR with everything needed to
 * mint the key by hand, and reported as NOT accepted so Stripe keeps retrying. That is deliberate:
 * silently succeeding here means a buyer paid and received nothing, and nobody finds out.
 */
public class HttpFulfillmentGateway implements FulfillmentGateway {

  private static final Logger LOG = LoggerFactory.getLogger(HttpFulfillmentGateway.class);

  private final String licensingUrl;
  private final String sharedSecret;
  private final HttpClient http;

  public HttpFulfillmentGateway(String licensingUrl, String sharedSecret) {
    this(
        licensingUrl,
        sharedSecret,
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
  }

  /** Client-injecting constructor - the HTTP hop is untestable without it. */
  public HttpFulfillmentGateway(String licensingUrl, String sharedSecret, HttpClient http) {
    this.licensingUrl = licensingUrl == null ? "" : licensingUrl.trim();
    this.sharedSecret = sharedSecret == null ? "" : sharedSecret;
    this.http = http;
  }

  public boolean isConfigured() {
    return !licensingUrl.isBlank();
  }

  @Override
  public boolean fulfil(PaymentProvider.Fulfillment fulfillment) {
    if (!isConfigured()) {
      LOG.error(
          "PAID ORDER NOT FULFILLED: no cassyx.licensing.url configured. Mint manually for "
              + "event={} email={} name={}",
          fulfillment.eventId(),
          fulfillment.email(),
          fulfillment.name());
      return false;
    }
    return post("/licensing/fulfillments", body(fulfillment, "purchase"));
  }

  @Override
  public void markFailed(PaymentProvider.Fulfillment fulfillment) {
    LOG.warn(
        "Stripe reported a failed asynchronous payment for event={} email={}; no licence minted",
        fulfillment.eventId(),
        fulfillment.email());
    if (isConfigured()) {
      post("/licensing/fulfillments/failed", body(fulfillment, "failed"));
    }
  }

  private boolean post(String path, String json) {
    try {
      HttpRequest.Builder request =
          HttpRequest.newBuilder(URI.create(licensingUrl + path))
              .timeout(Duration.ofSeconds(20))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
      if (!sharedSecret.isBlank()) {
        request.header("X-Cassyx-Licensing-Token", sharedSecret);
      }
      HttpResponse<String> response =
          http.send(request.build(), HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() / 100 == 2) {
        return true;
      }
      LOG.error(
          "Licensing service refused a fulfilment: HTTP {} {}", response.statusCode(), response.body());
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      LOG.error("Interrupted while forwarding a fulfilment to the licensing service", e);
      return false;
    } catch (RuntimeException | java.io.IOException e) {
      LOG.error("Could not reach the licensing service at {}", licensingUrl, e);
      return false;
    }
  }

  /** Hand-rolled rather than Jackson-mapped: three fields, and escaping is the only hazard. */
  private static String body(PaymentProvider.Fulfillment fulfillment, String kind) {
    return "{\"eventId\":"
        + quote(fulfillment.eventId())
        + ",\"email\":"
        + quote(fulfillment.email())
        + ",\"name\":"
        + quote(fulfillment.name())
        + ",\"kind\":"
        + quote(kind)
        + "}";
  }

  private static String quote(String value) {
    if (value == null) {
      return "null";
    }
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }
}
