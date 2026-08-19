package io.cassyx.api.billing;

import io.cassyx.license.api.PaymentProvider;

/**
 * Hands a verified, paid fulfilment to whatever can actually mint a licence.
 *
 * <p>Minting needs the Ed25519 PRIVATE key, which must never ship in the self-hosted image (plan
 * section 9.1), so this instance cannot do it: the operator-run {@code licensing/} service does.
 * The seam exists so that a self-hosted deployment with no licensing URL configured still answers
 * Stripe truthfully instead of pretending it fulfilled something.
 */
public interface FulfillmentGateway {

  /**
   * @return true when the fulfilment was accepted downstream; false means Stripe should retry
   */
  boolean fulfil(PaymentProvider.Fulfillment fulfillment);

  /** Records a failed asynchronous payment. Never mints anything. */
  void markFailed(PaymentProvider.Fulfillment fulfillment);
}
