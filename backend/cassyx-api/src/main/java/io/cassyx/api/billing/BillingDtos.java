package io.cassyx.api.billing;

import java.util.Map;

/**
 * Request/response bodies for {@code /api/billing/**}, mirroring the {@code CheckoutSessionRequest},
 * {@code CheckoutSessionResponse} and {@code WebhookAck} schemas in
 * {@code openapi/cassyx-api.yaml}. Per plan section 2.3 the contract governs these shapes.
 */
final class BillingDtos {

  private BillingDtos() {}

  record CheckoutSessionRequest(
      String email, Integer quantity, String successUrl, String cancelUrl,
      Map<String, String> metadata) {}

  record CheckoutSessionResponse(
      String sessionId, String url, String publishableKey, String expiresAt) {}

  /**
   * @param handled false for event types cassyx deliberately ignores
   * @param duplicate true when this {@code event.id} was already processed - Stripe retries
   *     webhooks, so a replay must be acknowledged without minting a second licence
   * @param action one of the contract's enum values: FULFILLED, IGNORED_UNPAID, MARKED_FAILED,
   *     IGNORED_UNHANDLED_TYPE, DUPLICATE
   */
  record WebhookAck(
      boolean received, String eventId, boolean handled, boolean duplicate, String action) {}
}
