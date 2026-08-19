package io.cassyx.api.billing;

import io.cassyx.api.billing.BillingDtos.CheckoutSessionRequest;
import io.cassyx.api.billing.BillingDtos.CheckoutSessionResponse;
import io.cassyx.api.billing.BillingDtos.WebhookAck;
import io.cassyx.api.config.BillingProperties;
import io.cassyx.license.api.PaymentProvider;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/billing/checkout-session} and {@code POST /api/billing/webhook} - the two
 * ungated billing paths (plan sections 9.1, 9.3). Unlicensed users must be able to buy, so neither
 * may ever sit behind the licence gate.
 *
 * <p>The webhook body is taken as a {@code String} rather than a mapped object on purpose: the
 * signature covers the RAW bytes, so any parse-then-re-serialise round trip invalidates it and
 * (worse) means parsing untrusted input before it is verified. cassyx-api has no Spring Security on
 * the classpath, so there is no CSRF filter to exempt; if one is ever added, this path must be
 * excluded explicitly - noted in docs/integration-todo.md.
 */
@RestController
public class BillingController {

  private static final Logger LOG = LoggerFactory.getLogger(BillingController.class);

  private static final String PROBLEM_BASE = "https://cassyx.dev/problems/";

  private final PaymentProvider provider;
  private final BillingProperties properties;
  private final ProcessedEventStore events;
  private final FulfillmentGateway fulfillment;

  public BillingController(
      PaymentProvider provider,
      BillingProperties properties,
      ProcessedEventStore events,
      FulfillmentGateway fulfillment) {
    this.provider = provider;
    this.properties = properties;
    this.events = events;
    this.fulfillment = fulfillment;
  }

  @PostMapping(path = "/api/billing/checkout-session", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<?> createCheckoutSession(@RequestBody CheckoutSessionRequest request) {
    if (request == null || request.email() == null || request.email().isBlank()) {
      return ResponseEntity.badRequest()
          .contentType(MediaType.APPLICATION_PROBLEM_JSON)
          .body(problem(HttpStatus.BAD_REQUEST, "invalid-request", "Missing email",
              "An email address is required: it prefills Checkout and is where the licence is sent."));
    }
    if (!properties.enabled() || "noop".equalsIgnoreCase(provider.id())) {
      // Honest 503 rather than a fake session URL. The UI shows the "billing disabled" state and
      // points at offline activation instead (plan section 9.2).
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
          .contentType(MediaType.APPLICATION_PROBLEM_JSON)
          .body(problem(HttpStatus.SERVICE_UNAVAILABLE, "billing-disabled", "Billing is disabled",
              "cassyx.billing.enabled is false or no payment provider is configured. "
                  + "Buy through the vendor and activate offline at /api/license/activate."));
    }
    Map<String, String> metadata = new HashMap<>();
    if (request.metadata() != null) {
      metadata.putAll(request.metadata());
    }
    if (request.quantity() != null && request.quantity() > 0) {
      // The SPI's CheckoutRequest carries no quantity; StripePaymentProvider reads it from here.
      metadata.put("quantity", String.valueOf(request.quantity()));
    }
    metadata.putIfAbsent("cassyx_email", request.email());
    try {
      PaymentProvider.CheckoutSession session =
          provider.createCheckout(
              new PaymentProvider.CheckoutRequest(
                  properties.priceId(),
                  request.email(),
                  successUrl(request),
                  cancelUrl(request),
                  null,
                  metadata));
      return ResponseEntity.status(HttpStatus.CREATED)
          .body(
              new CheckoutSessionResponse(
                  session.id(), session.url(), properties.publishableKey(), null));
    } catch (IllegalStateException e) {
      LOG.error("Could not create a Stripe checkout session", e);
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
          .contentType(MediaType.APPLICATION_PROBLEM_JSON)
          .body(problem(HttpStatus.SERVICE_UNAVAILABLE, "billing-unavailable",
              "Checkout is unavailable", e.getMessage()));
    }
  }

  /**
   * Stripe's return page carries the session id, but fulfilment never depends on it being reached -
   * a buyer can pay and lose connectivity before it loads.
   */
  private String successUrl(CheckoutSessionRequest request) {
    String base = request.successUrl() != null && !request.successUrl().isBlank()
        ? request.successUrl()
        : properties.successUrl();
    if (base == null || base.contains("{CHECKOUT_SESSION_ID}")) {
      return base;
    }
    return base + (base.contains("?") ? "&" : "?") + "session_id={CHECKOUT_SESSION_ID}";
  }

  private String cancelUrl(CheckoutSessionRequest request) {
    return request.cancelUrl() != null && !request.cancelUrl().isBlank()
        ? request.cancelUrl()
        : properties.cancelUrl();
  }

  /**
   * Webhook receiver. Order is non-negotiable: verify the signature over the raw body, THEN claim
   * the event id, THEN act. Anything else either trusts unverified input or mints twice.
   */
  @PostMapping(path = "/api/billing/webhook", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<?> webhook(
      @RequestBody(required = false) String rawBody,
      @RequestHeader(value = "Stripe-Signature", required = false) String signature) {
    Optional<PaymentProvider.WebhookEvent> verified =
        provider.verifyWebhook(
            rawBody == null ? "" : rawBody,
            signature == null ? Map.of() : Map.of("Stripe-Signature", signature));
    if (verified.isEmpty()) {
      // Nothing was parsed and nothing was acted on. 400 also stops Stripe retrying forever.
      return ResponseEntity.badRequest()
          .contentType(MediaType.APPLICATION_PROBLEM_JSON)
          .body(problem(HttpStatus.BAD_REQUEST, "webhook-signature", "Signature verification failed",
              "The Stripe-Signature header does not match the request body."));
    }
    PaymentProvider.WebhookEvent event = verified.get();
    if (!events.claim(event.id(), event.type())) {
      return ResponseEntity.ok(new WebhookAck(true, event.id(), true, true, "DUPLICATE"));
    }
    try {
      return ResponseEntity.ok(handle(event));
    } catch (RuntimeException e) {
      // Release the claim: keeping it would turn one transient failure into a permanently
      // unfulfilled order that has already been charged.
      events.release(event.id());
      LOG.error("Fulfilment failed for Stripe event {}; released for retry", event.id(), e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .contentType(MediaType.APPLICATION_PROBLEM_JSON)
          .body(problem(HttpStatus.INTERNAL_SERVER_ERROR, "fulfilment-failed",
              "Fulfilment failed", "The event was verified but could not be fulfilled. Retry."));
    }
  }

  private WebhookAck handle(PaymentProvider.WebhookEvent event) {
    Optional<PaymentProvider.Fulfillment> parsed = provider.parseFulfillment(event);
    if (parsed.isEmpty()) {
      events.complete(event.id(), "IGNORED_UNHANDLED_TYPE", null, event.type());
      return new WebhookAck(true, event.id(), false, false, "IGNORED_UNHANDLED_TYPE");
    }
    PaymentProvider.Fulfillment order = parsed.get();
    if (StripeEvents.ASYNC_FAILED.equals(event.type())) {
      fulfillment.markFailed(order);
      events.complete(event.id(), "MARKED_FAILED", order.email(), "async payment failed");
      return new WebhookAck(true, event.id(), true, false, "MARKED_FAILED");
    }
    if (!order.paid()) {
      // checkout.session.completed with payment_status=unpaid: the session is done, the money is
      // not. Fulfilling here ships a licence for a payment that may never settle; the matching
      // async_payment_succeeded event is what actually authorises it.
      events.complete(event.id(), "IGNORED_UNPAID", order.email(), "payment_status=unpaid");
      return new WebhookAck(true, event.id(), true, false, "IGNORED_UNPAID");
    }
    if (!fulfillment.fulfil(order)) {
      throw new IllegalStateException("licensing service did not accept the fulfilment");
    }
    events.complete(event.id(), "FULFILLED", order.email(), null);
    return new WebhookAck(true, event.id(), true, false, "FULFILLED");
  }

  private static ProblemDetail problem(HttpStatus status, String type, String title, String detail) {
    ProblemDetail problem = ProblemDetail.forStatus(status);
    problem.setType(URI.create(PROBLEM_BASE + type));
    problem.setTitle(title);
    problem.setDetail(detail);
    return problem;
  }
}
