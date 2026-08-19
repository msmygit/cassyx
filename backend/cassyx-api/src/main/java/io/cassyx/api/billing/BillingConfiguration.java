package io.cassyx.api.billing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring for the billing endpoints (plan section 9.3).
 *
 * <p>The {@code PaymentProvider} bean itself lives in {@code CassyxCoreConfiguration} and is
 * ServiceLoader-selected by id. {@code StripePaymentProvider} reads its credentials from the
 * environment ({@code STRIPE_SECRET_KEY}, {@code STRIPE_WEBHOOK_SECRET},
 * {@code CASSYX_BILLING_API_URL}) - the same variables {@code cassyx.billing.*} is bound from -
 * because cassyx-api may not import a sibling's {@code impl} package to hand it typed config
 * (plan section 2.1). See docs/integration-todo.md for the SPI change that would remove the
 * duplication.
 */
@Configuration
public class BillingConfiguration {

  private static final Logger LOG = LoggerFactory.getLogger(BillingConfiguration.class);

  /**
   * Where verified fulfilments go. Unset by default: the self-hosted image cannot mint licences at
   * all (it holds only the public key), so the honest default is a gateway that logs loudly and
   * reports failure so Stripe retries.
   */
  @Bean
  public FulfillmentGateway fulfillmentGateway(
      @Value("${cassyx.licensing.url:}") String licensingUrl,
      @Value("${cassyx.licensing.token:}") String token) {
    if (licensingUrl == null || licensingUrl.isBlank()) {
      LOG.info(
          "cassyx.licensing.url is not set: this instance verifies licences but cannot mint them. "
              + "Point Stripe's webhook at the operator-run licensing/ service instead.");
    }
    return new HttpFulfillmentGateway(licensingUrl, token);
  }
}
