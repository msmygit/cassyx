package io.cassyx.license.api;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * SPI abstracting the payment processor (plan section 9.3), so the product can be resold through a
 * different processor without touching license logic. {@link ServiceLoader}-discovered:
 * {@code stripe} and {@code noop} today.
 */
public interface PaymentProvider {

  /** Stable id: {@code stripe}, {@code noop}. */
  String id();

  /**
   * Creates a hosted checkout for a ONE-TIME payment (Stripe: Checkout Sessions with
   * {@code mode: "payment"}; never PaymentIntents, never the Charges API).
   */
  CheckoutSession createCheckout(CheckoutRequest request);

  /**
   * Verifies the webhook signature over the RAW request body before anything is parsed.
   *
   * @return the verified event, or empty when the signature does not match
   */
  Optional<WebhookEvent> verifyWebhook(String rawBody, Map<String, String> headers);

  /**
   * Extracts fulfilment instructions from a verified event. Fulfilment is webhook-driven, never
   * success-page-driven: a buyer can pay and lose connectivity before the return page loads.
   */
  Optional<Fulfillment> parseFulfillment(WebhookEvent event);

  static PaymentProvider forId(String id) {
    for (PaymentProvider provider : ServiceLoader.load(PaymentProvider.class)) {
      if (provider.id().equalsIgnoreCase(id)) {
        return provider;
      }
    }
    throw new IllegalArgumentException("No PaymentProvider registered for id '" + id + "'");
  }

  static List<PaymentProvider> available() {
    List<PaymentProvider> providers = new java.util.ArrayList<>();
    ServiceLoader.load(PaymentProvider.class).forEach(providers::add);
    return List.copyOf(providers);
  }

  /**
   * @param integrationIdentifier label plus 8 random letters, for Dashboard attribution
   */
  record CheckoutRequest(
      String priceId,
      String customerEmail,
      String successUrl,
      String cancelUrl,
      String integrationIdentifier,
      Map<String, String> metadata) {

    public CheckoutRequest {
      metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
  }

  record CheckoutSession(String id, String url, String provider) {}

  /**
   * @param id used for idempotency - handlers must be idempotent on the event id
   * @param payload the parsed event body, provider-specific
   */
  record WebhookEvent(String id, String type, Map<String, Object> payload) {

    public WebhookEvent {
      payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
  }

  /**
   * Instruction to mint, persist and email an Ed25519-signed license.
   *
   * @param paid false for {@code checkout.session.completed} with
   *     {@code payment_status == "unpaid"} - those must NOT be fulfilled
   */
  record Fulfillment(String eventId, String email, String name, boolean paid) {}
}
