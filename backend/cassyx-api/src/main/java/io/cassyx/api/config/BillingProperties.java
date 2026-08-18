package io.cassyx.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code cassyx.billing.*} - plan section 9.3. Every value is a placeholder in the repo; real keys
 * come from the environment. The secret key is expected to be a RESTRICTED key ({@code rk_}).
 */
@ConfigurationProperties(prefix = "cassyx.billing")
public record BillingProperties(
    boolean enabled,
    String provider,
    String apiBaseUrl,
    String publishableKey,
    String secretKey,
    String webhookSecret,
    String priceId,
    String successUrl,
    String cancelUrl) {}
