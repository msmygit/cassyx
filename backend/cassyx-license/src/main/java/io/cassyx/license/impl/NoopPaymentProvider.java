package io.cassyx.license.impl;

import io.cassyx.license.api.PaymentProvider;
import java.util.Map;
import java.util.Optional;

/**
 * Reference {@link PaymentProvider}: no processor at all. Used when
 * {@code cassyx.billing.enabled=false}, in CI, and for self-hosted or enterprise site-licence
 * deployments where purchasing is out of band (plan sections 9.2 and 9.3).
 */
public final class NoopPaymentProvider implements PaymentProvider {

  @Override
  public String id() {
    return "noop";
  }

  @Override
  public CheckoutSession createCheckout(CheckoutRequest request) {
    // No network call, no charge. The UI shows the "billing disabled" state instead of a redirect.
    return new CheckoutSession("noop-session", null, id());
  }

  @Override
  public Optional<WebhookEvent> verifyWebhook(String rawBody, Map<String, String> headers) {
    // Nothing can be verified without a processor: reject rather than trust.
    return Optional.empty();
  }

  @Override
  public Optional<Fulfillment> parseFulfillment(WebhookEvent event) {
    return Optional.empty();
  }
}
