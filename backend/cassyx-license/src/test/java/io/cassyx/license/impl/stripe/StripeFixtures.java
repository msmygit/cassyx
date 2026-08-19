package io.cassyx.license.impl.stripe;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Stripe event fixtures and a real {@code Stripe-Signature} header builder. Deliberately computes
 * the HMAC the way Stripe does rather than stubbing verification out: the whole point of the
 * webhook tests is that the genuine {@code Webhook.constructEvent} accepts a genuine signature and
 * rejects a tampered body. No network is involved.
 */
final class StripeFixtures {

  static final String WEBHOOK_SECRET = "whsec_test_secret_for_unit_tests_only";

  private StripeFixtures() {}

  /** {@code checkout.session.completed} with the given payment status. */
  static String completed(String eventId, String paymentStatus) {
    return sessionEvent(eventId, "checkout.session.completed", paymentStatus);
  }

  static String asyncSucceeded(String eventId) {
    return sessionEvent(eventId, "checkout.session.async_payment_succeeded", "paid");
  }

  static String asyncFailed(String eventId) {
    return sessionEvent(eventId, "checkout.session.async_payment_failed", "unpaid");
  }

  static String sessionEvent(String eventId, String type, String paymentStatus) {
    return """
        {
          "id": "%s",
          "object": "event",
          "api_version": "2026-07-29.dahlia",
          "created": 1755388800,
          "type": "%s",
          "data": {
            "object": {
              "id": "cs_test_a1PLACEHOLDER0000000000",
              "object": "checkout.session",
              "mode": "payment",
              "payment_status": "%s",
              "status": "complete",
              "customer_email": "prefilled@example.com",
              "customer_details": { "email": "ops@example.com", "name": "Example GmbH" },
              "metadata": { "cassyx_email": "ops@example.com", "cassyx_name": "Example GmbH" }
            }
          }
        }
        """
        .formatted(eventId, type, paymentStatus);
  }

  /** An event type cassyx does not fulfil on; must be acknowledged and ignored. */
  static String unrelatedEvent(String eventId) {
    return """
        { "id": "%s", "object": "event", "type": "payment_intent.created",
          "created": 1755388800, "data": { "object": { "id": "pi_test" } } }
        """
        .formatted(eventId);
  }

  /** The header Stripe sends: {@code t=<unix>,v1=<hex hmac of "t.payload">}. */
  static String signatureHeader(String payload, String secret) {
    long timestamp = System.currentTimeMillis() / 1000L;
    return "t=" + timestamp + ",v1=" + hmac(timestamp + "." + payload, secret);
  }

  private static String hmac(String signedPayload, String secret) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(signedPayload.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }
}
