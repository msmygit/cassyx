package io.cassyx.licensing.web;

import io.cassyx.license.api.PaymentProvider;
import io.cassyx.licensing.config.LicensingProperties;
import io.cassyx.licensing.service.LicensingService;
import io.cassyx.licensing.store.IssuedLicense;
import io.cassyx.licensing.store.IssuedLicenseRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * The licensing service's HTTP surface (plan sections 9.3, 9.4).
 *
 * <ul>
 *   <li>{@code POST /licensing/webhook} - Stripe delivers here directly in the recommended
 *       deployment. Signature verified over the RAW body, idempotent on {@code event.id}.
 *   <li>{@code POST /licensing/fulfillments} - a cassyx instance that verified the webhook itself
 *       forwards the result here. Guarded by a shared token, since it mints on request.
 *   <li>{@code POST /licensing/trial} - one time-limited key per address.
 *   <li>{@code POST /licensing/recover} - "email me my key again". Unauthenticated by necessity
 *       (the person asking has lost the only thing they had) and therefore deliberately incapable
 *       of revealing whether an address is a customer.
 * </ul>
 */
@RestController
public class LicensingController {

  private static final Logger LOG = LoggerFactory.getLogger(LicensingController.class);

  private static final String TOKEN_HEADER = "X-Cassyx-Licensing-Token";

  private final LicensingService licensing;
  private final PaymentProvider payments;
  private final IssuedLicenseRepository repository;
  private final LicensingProperties properties;

  public LicensingController(
      LicensingService licensing,
      PaymentProvider payments,
      IssuedLicenseRepository repository,
      LicensingProperties properties) {
    this.licensing = licensing;
    this.payments = payments;
    this.repository = repository;
    this.properties = properties;
  }

  @GetMapping("/licensing/health")
  public Map<String, Object> health() {
    return Map.of("status", "UP", "provider", payments.id());
  }

  /**
   * Stripe webhook. Same rules as the product's receiver: verify the raw body first, claim the
   * event id second, act third.
   */
  @PostMapping(path = "/licensing/webhook", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<?> webhook(
      @RequestBody(required = false) String rawBody,
      @RequestHeader(value = "Stripe-Signature", required = false) String signature) {
    Optional<PaymentProvider.WebhookEvent> verified =
        payments.verifyWebhook(
            rawBody == null ? "" : rawBody,
            signature == null ? Map.of() : Map.of("Stripe-Signature", signature));
    if (verified.isEmpty()) {
      return ResponseEntity.badRequest().body(Map.of("received", false, "error", "bad signature"));
    }
    PaymentProvider.WebhookEvent event = verified.get();
    if (!repository.claimEvent(event.id(), event.type())) {
      return ResponseEntity.ok(Map.of("received", true, "duplicate", true, "action", "DUPLICATE"));
    }
    Optional<PaymentProvider.Fulfillment> parsed = payments.parseFulfillment(event);
    if (parsed.isEmpty()) {
      repository.completeEvent(event.id(), "IGNORED_UNHANDLED_TYPE", null, event.type());
      return ResponseEntity.ok(
          Map.of("received", true, "handled", false, "action", "IGNORED_UNHANDLED_TYPE"));
    }
    PaymentProvider.Fulfillment order = parsed.get();
    if (!order.paid()) {
      // checkout.session.completed with payment_status=unpaid, or async_payment_failed. Neither
      // authorises a licence; the money has not arrived.
      repository.completeEvent(event.id(), "NOT_PAID", order.email(), event.type());
      return ResponseEntity.ok(Map.of("received", true, "handled", true, "action", "NOT_PAID"));
    }
    try {
      IssuedLicense issued =
          licensing.issuePurchase(order.email(), order.name(), order.eventId());
      repository.completeEvent(event.id(), "FULFILLED", order.email(), issued.licCode());
      return ResponseEntity.ok(
          Map.of("received", true, "handled", true, "action", "FULFILLED", "lic", issued.licCode()));
    } catch (RuntimeException e) {
      repository.releaseEvent(event.id());
      LOG.error("Minting failed for Stripe event {}; released for retry", event.id(), e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(Map.of("received", false, "error", "minting failed"));
    }
  }

  /** Fulfilment forwarded by a cassyx instance that already verified the Stripe signature. */
  @PostMapping(path = "/licensing/fulfillments", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<?> fulfil(
      @RequestBody FulfillmentRequest request,
      @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
    if (!authorised(token)) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "unauthorised"));
    }
    if (request == null || request.email() == null || request.email().isBlank()) {
      return ResponseEntity.badRequest().body(Map.of("error", "email is required"));
    }
    if (request.eventId() != null && !repository.claimEvent(request.eventId(), "forwarded")) {
      return ResponseEntity.ok(Map.of("duplicate", true, "action", "DUPLICATE"));
    }
    IssuedLicense issued =
        licensing.issuePurchase(request.email(), request.name(), request.eventId());
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            Map.of(
                "lic", issued.licCode(),
                "edition", issued.edition(),
                "delivery", issued.deliveryState()));
  }

  /** A failed asynchronous payment. Recorded, never minted. */
  @PostMapping(path = "/licensing/fulfillments/failed", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<?> failed(
      @RequestBody FulfillmentRequest request,
      @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
    if (!authorised(token)) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "unauthorised"));
    }
    LOG.warn(
        "Payment failed for {} (event {}); no licence minted",
        request == null ? null : request.email(),
        request == null ? null : request.eventId());
    return ResponseEntity.accepted().body(Map.of("recorded", true));
  }

  /** {@code POST /licensing/trial} - 409 rather than silently re-arming the clock. */
  @PostMapping(path = "/licensing/trial", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<?> trial(@RequestBody TrialRequest request) {
    if (request == null || request.email() == null || request.email().isBlank()) {
      return ResponseEntity.badRequest().body(Map.of("error", "email is required"));
    }
    Optional<IssuedLicense> issued = licensing.issueTrial(request.email(), request.name());
    if (issued.isEmpty()) {
      return ResponseEntity.status(HttpStatus.CONFLICT)
          .body(
              Map.of(
                  "error", "a trial has already been issued for this address",
                  "recover", "/licensing/recover"));
    }
    IssuedLicense trial = issued.get();
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            Map.of(
                "lic", trial.licCode(),
                "edition", trial.edition(),
                "expires", String.valueOf(trial.expiresOn()),
                "licenseKey", trial.licenseKey(),
                "delivery", trial.deliveryState()));
  }

  /**
   * Self-serve recovery. Always 202 with the same body whether or not the address is known: a
   * different answer for a known address turns this into a customer-list oracle.
   */
  @PostMapping(path = "/licensing/recover", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<?> recover(@RequestBody TrialRequest request) {
    if (request == null || request.email() == null || request.email().isBlank()) {
      return ResponseEntity.badRequest().body(Map.of("error", "email is required"));
    }
    int sent = licensing.recover(request.email());
    LOG.info("Recovery request processed; {} key(s) re-sent", sent);
    return ResponseEntity.accepted()
        .body(
            Map.of(
                "accepted", true,
                "message", "If that address has a licence, the key is on its way."));
    }

  /** Operator endpoint: re-attempt every key that was minted but never delivered. */
  @PostMapping(path = "/licensing/deliveries/retry", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<?> retryDeliveries(
      @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
    if (!authorised(token)) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "unauthorised"));
    }
    List<IssuedLicense> pending = repository.findUndelivered();
    int sent = licensing.retryUndelivered();
    return ResponseEntity.ok(Map.of("pending", pending.size(), "sent", sent));
  }

  /**
   * An unset token means the internal endpoints are open, which is only ever acceptable on a
   * loopback dev instance - so it is logged every time rather than silently allowed.
   */
  private boolean authorised(String token) {
    String expected = properties.token();
    if (expected == null || expected.isBlank() || expected.contains("PLACEHOLDER")) {
      LOG.warn("CASSYX_LICENSING_TOKEN is not set: internal minting endpoints are UNAUTHENTICATED");
      return true;
    }
    return expected.equals(token);
  }

  /** Body of {@code /licensing/fulfillments}, matching HttpFulfillmentGateway in cassyx-api. */
  public record FulfillmentRequest(String eventId, String email, String name, String kind) {}

  public record TrialRequest(String email, String name) {}
}
