package io.cassyx.api.billing;

/**
 * The three Stripe event types cassyx acts on (plan section 9.3).
 *
 * <p>Restated here as literals rather than imported from {@code io.cassyx.license.impl.stripe}: only
 * a module's {@code ...api} package is public surface (plan section 2.1), and ModularityArchitecture
 * Test fails the build on any import of a sibling's {@code impl} package.
 */
final class StripeEvents {

  /** Fulfil only when {@code payment_status != "unpaid"}. */
  static final String COMPLETED = "checkout.session.completed";

  /** A delayed-notification payment settled: fulfil. */
  static final String ASYNC_SUCCEEDED = "checkout.session.async_payment_succeeded";

  /** A delayed-notification payment failed: mark failed, never mint. */
  static final String ASYNC_FAILED = "checkout.session.async_payment_failed";

  private StripeEvents() {}
}
