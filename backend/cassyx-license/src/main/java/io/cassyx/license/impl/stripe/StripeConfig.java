package io.cassyx.license.impl.stripe;

import java.security.SecureRandom;
import java.util.Locale;

/**
 * Everything {@link StripePaymentProvider} needs from configuration (plan section 9.3). Kept out of
 * Spring on purpose: cassyx-license must stay framework-free, so the API module maps
 * {@code cassyx.billing.*} onto this record and the licensing service maps its own environment onto
 * the same one.
 *
 * @param secretKey a RESTRICTED key ({@code rk_}) rather than a secret key
 * @param webhookSecret {@code whsec_...}, used for the raw-body signature check
 * @param apiBaseUrl overridable so tests and sandboxes never talk to api.stripe.com
 * @param integrationIdentifier Dashboard attribution label; see {@link #withGeneratedIdentifier}
 */
public record StripeConfig(
    String secretKey, String webhookSecret, String apiBaseUrl, String integrationIdentifier) {

  public static final String DEFAULT_API_BASE_URL = "https://api.stripe.com";

  /** Label half of the integration identifier; the suffix is 8 random letters. */
  public static final String INTEGRATION_LABEL = "cassyx-selfhosted";

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final int SUFFIX_LENGTH = 8;

  /**
   * A value that is still the shipped placeholder is treated as "not configured". Placeholders are
   * deliberate (no live key is ever committed), so the honest response to one is 503 rather than a
   * confusing 401 from Stripe.
   */
  private static final String PLACEHOLDER = "PLACEHOLDER";

  public StripeConfig {
    apiBaseUrl = blank(apiBaseUrl) ? DEFAULT_API_BASE_URL : apiBaseUrl.trim();
  }

  /** No-arg ServiceLoader discovery has nothing to inject, so the environment is the fallback. */
  public static StripeConfig fromEnvironment() {
    return new StripeConfig(
        System.getenv("STRIPE_SECRET_KEY"),
        System.getenv("STRIPE_WEBHOOK_SECRET"),
        System.getenv("CASSYX_BILLING_API_URL"),
        System.getenv("CASSYX_BILLING_INTEGRATION_ID"));
  }

  /**
   * Stripe asks for a label plus 8 random letters. The suffix is generated once per instance rather
   * than per request: it identifies the integration in the Dashboard, so a fresh suffix on every
   * checkout would scatter one funnel across thousands of labels. Operators who want it stable
   * across restarts set {@code CASSYX_BILLING_INTEGRATION_ID} explicitly.
   */
  public StripeConfig withGeneratedIdentifier() {
    if (!blank(integrationIdentifier)) {
      return this;
    }
    StringBuilder suffix = new StringBuilder(SUFFIX_LENGTH);
    for (int i = 0; i < SUFFIX_LENGTH; i++) {
      suffix.append((char) ('a' + RANDOM.nextInt(26)));
    }
    return new StripeConfig(
        secretKey, webhookSecret, apiBaseUrl, INTEGRATION_LABEL + "-" + suffix);
  }

  /** True once a real restricted key is present. Placeholders and blanks are "not configured". */
  public boolean hasApiKey() {
    return usable(secretKey);
  }

  /** True once a real webhook secret is present; without it nothing can be verified. */
  public boolean hasWebhookSecret() {
    return usable(webhookSecret);
  }

  private static boolean usable(String value) {
    return !blank(value) && !value.toUpperCase(Locale.ROOT).contains(PLACEHOLDER);
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }
}
