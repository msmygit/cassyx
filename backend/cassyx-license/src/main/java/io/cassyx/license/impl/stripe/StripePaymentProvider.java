package io.cassyx.license.impl.stripe;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.StripeClient;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import com.stripe.param.checkout.SessionCreateParams;
import io.cassyx.license.api.PaymentProvider;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stripe implementation of the {@link PaymentProvider} SPI (plan section 9.3).
 *
 * <p>Four rules here are not stylistic preferences, they are the difference between a working
 * integration and a broken one:
 *
 * <ul>
 *   <li><b>Checkout Sessions with {@code mode: "payment"}</b> - the recommended surface for one-time
 *       payments. Not PaymentIntents (those are for off-session), never the Charges API.
 *   <li><b>An instantiated {@link StripeClient}</b>, never the deprecated global
 *       {@code Stripe.apiKey} pattern. The global is process-wide mutable state, so two components
 *       with different keys silently fight over it.
 *   <li><b>No {@code payment_method_types}</b>. Omitting it enables dynamic payment methods, which
 *       is both the current recommendation and better for conversion. Restrict later with
 *       {@code payment_method_configurations} or {@code excluded_payment_method_types}.
 *   <li><b>Signature verified over the RAW body</b> before anything is parsed - a re-serialised body
 *       no longer matches the signature, and parsing first means acting on unverified input.
 * </ul>
 *
 * <p>Fulfilment is webhook-driven, never success-page-driven: a buyer can pay and lose connectivity
 * before the return page loads, and success-page fulfilment silently drops those orders.
 */
public final class StripePaymentProvider implements PaymentProvider {

  private static final Logger LOG = LoggerFactory.getLogger(StripePaymentProvider.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  /** The one payment status that must never be fulfilled (plan section 9.3). */
  static final String UNPAID = "unpaid";

  public static final String EVENT_COMPLETED = "checkout.session.completed";
  public static final String EVENT_ASYNC_SUCCEEDED = "checkout.session.async_payment_succeeded";
  public static final String EVENT_ASYNC_FAILED = "checkout.session.async_payment_failed";

  /** Case-insensitive, because header maps arrive from servlet containers in either casing. */
  private static final String SIGNATURE_HEADER = "stripe-signature";

  /** Read from checkout metadata: the SPI's CheckoutRequest carries no quantity of its own. */
  static final String METADATA_QUANTITY = "quantity";

  private final StripeConfig config;
  private final StripeClient client;

  /** ServiceLoader entry point: configuration comes from the environment. */
  public StripePaymentProvider() {
    this(StripeConfig.fromEnvironment());
  }

  public StripePaymentProvider(StripeConfig config) {
    this(config, null);
  }

  /**
   * @param client injected by tests (over a stub {@code HttpClient}) so no test ever reaches the
   *     network; null in production, where the client is built from {@code config}
   */
  public StripePaymentProvider(StripeConfig config, StripeClient client) {
    this.config = Objects.requireNonNull(config, "config").withGeneratedIdentifier();
    this.client = client != null ? client : buildClient(this.config);
  }

  private static StripeClient buildClient(StripeConfig config) {
    if (!config.hasApiKey()) {
      // A placeholder key is a configuration gap, not a fatal error: the provider must still be
      // constructible so /api/billing/* can answer "billing is not configured" rather than the
      // whole application failing to start.
      return null;
    }
    return StripeClient.builder()
        .setApiKey(config.secretKey())
        .setApiBase(config.apiBaseUrl())
        .build();
  }

  @Override
  public String id() {
    return "stripe";
  }

  /** True once a usable restricted key is present; the API layer answers 503 when it is not. */
  public boolean isConfigured() {
    return client != null;
  }

  public StripeConfig config() {
    return config;
  }

  @Override
  public CheckoutSession createCheckout(CheckoutRequest request) {
    Objects.requireNonNull(request, "request");
    if (client == null) {
      throw new IllegalStateException(
          "Stripe is not configured: set STRIPE_SECRET_KEY to a restricted (rk_) key");
    }
    SessionCreateParams.Builder params =
        SessionCreateParams.builder()
            // One-time purchase (plan section 9). Subscriptions would be mode=subscription.
            .setMode(SessionCreateParams.Mode.PAYMENT)
            .addLineItem(
                SessionCreateParams.LineItem.builder()
                    .setPrice(request.priceId())
                    .setQuantity(quantityOf(request))
                    .build())
            .setSuccessUrl(request.successUrl())
            .setCancelUrl(request.cancelUrl())
            // Dashboard attribution for this checkout flow (label + 8 random letters).
            .setIntegrationIdentifier(
                request.integrationIdentifier() != null
                    ? request.integrationIdentifier()
                    : config.integrationIdentifier());
    if (request.customerEmail() != null && !request.customerEmail().isBlank()) {
      params.setCustomerEmail(request.customerEmail());
      // Metadata survives onto the webhook event, which is where the licence is actually minted -
      // the buyer's address must be recoverable there even if Checkout collected a different one.
      params.putMetadata("cassyx_email", request.customerEmail());
    }
    params.putAllMetadata(request.metadata());
    // NOTE: no setPaymentMethodType / addPaymentMethodType call. Deliberate - see the class javadoc.
    try {
      // client.v1(), not the flat client.checkout() accessor: the latter is deprecated in 33.x.
      Session session = client.v1().checkout().sessions().create(params.build());
      return new CheckoutSession(session.getId(), session.getUrl(), id());
    } catch (StripeException e) {
      throw new IllegalStateException("Stripe rejected the checkout session: " + e.getMessage(), e);
    }
  }

  /**
   * The SPI's {@code CheckoutRequest} has no quantity field, so seat counts ride in metadata. A
   * malformed value falls back to 1 rather than failing the purchase outright.
   */
  private static long quantityOf(CheckoutRequest request) {
    String raw = request.metadata().get(METADATA_QUANTITY);
    if (raw == null || raw.isBlank()) {
      return 1L;
    }
    try {
      return Math.max(1L, Long.parseLong(raw.trim()));
    } catch (NumberFormatException e) {
      return 1L;
    }
  }

  @Override
  public Optional<WebhookEvent> verifyWebhook(String rawBody, Map<String, String> headers) {
    if (rawBody == null || headers == null) {
      return Optional.empty();
    }
    if (!config.hasWebhookSecret()) {
      // Refusing beats trusting: without a secret there is nothing standing between the endpoint
      // and anyone who can POST JSON at it.
      LOG.warn("Rejected a Stripe webhook: STRIPE_WEBHOOK_SECRET is not configured");
      return Optional.empty();
    }
    String signature = header(headers, SIGNATURE_HEADER);
    if (signature == null) {
      return Optional.empty();
    }
    try {
      // Verify FIRST, over the exact bytes Stripe signed. Any re-serialisation invalidates it.
      Event event = Webhook.constructEvent(rawBody, signature, config.webhookSecret());
      Map<String, Object> payload = MAPPER.readValue(rawBody, MAP_TYPE);
      return Optional.of(new WebhookEvent(event.getId(), event.getType(), payload));
    } catch (SignatureVerificationException e) {
      LOG.warn("Rejected a Stripe webhook: signature does not match ({})", e.getMessage());
      return Optional.empty();
    } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
      // A signed-but-unreadable body is a Stripe-side change, not an attack; still not fulfillable.
      LOG.warn("Rejected a Stripe webhook: body verified but is not readable JSON", e);
      return Optional.empty();
    }
  }

  @Override
  public Optional<Fulfillment> parseFulfillment(WebhookEvent event) {
    if (event == null || event.type() == null) {
      return Optional.empty();
    }
    Map<String, Object> session = sessionObject(event);
    if (session == null) {
      return Optional.empty();
    }
    String email = emailOf(session);
    String name = nameOf(session);
    return switch (event.type()) {
      // The single most expensive mistake available here: a completed session with
      // payment_status=unpaid is a delayed-notification method that has NOT paid yet. Fulfilling it
      // ships a licence for money that may never arrive.
      case EVENT_COMPLETED ->
          Optional.of(
              new Fulfillment(event.id(), email, name, !UNPAID.equals(paymentStatus(session))));
      case EVENT_ASYNC_SUCCEEDED -> Optional.of(new Fulfillment(event.id(), email, name, true));
      case EVENT_ASYNC_FAILED -> Optional.of(new Fulfillment(event.id(), email, name, false));
      default -> Optional.empty();
    };
  }

  private static String paymentStatus(Map<String, Object> session) {
    Object status = session.get("payment_status");
    return status == null ? null : status.toString().toLowerCase(Locale.ROOT);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> sessionObject(WebhookEvent event) {
    Object data = event.payload().get("data");
    if (!(data instanceof Map<?, ?> dataMap)) {
      return null;
    }
    Object object = ((Map<String, Object>) dataMap).get("object");
    return object instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
  }

  /**
   * Checkout may collect an address different from the one prefilled, so the collected one wins;
   * the metadata copy is the last resort that keeps a purchase fulfillable at all.
   */
  private static String emailOf(Map<String, Object> session) {
    String collected = nested(session, "customer_details", "email");
    if (collected != null) {
      return collected;
    }
    Object prefilled = session.get("customer_email");
    if (prefilled != null) {
      return prefilled.toString();
    }
    return metadata(session, "cassyx_email");
  }

  private static String nameOf(Map<String, Object> session) {
    String collected = nested(session, "customer_details", "name");
    return collected != null ? collected : metadata(session, "cassyx_name");
  }

  @SuppressWarnings("unchecked")
  private static String nested(Map<String, Object> session, String outer, String key) {
    Object value = session.get(outer);
    if (value instanceof Map<?, ?> map) {
      Object inner = ((Map<String, Object>) map).get(key);
      return inner == null ? null : inner.toString();
    }
    return null;
  }

  private static String metadata(Map<String, Object> session, String key) {
    return nested(session, "metadata", key);
  }

  private static String header(Map<String, String> headers, String name) {
    for (Map.Entry<String, String> entry : headers.entrySet()) {
      if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
        return entry.getValue();
      }
    }
    return null;
  }
}
