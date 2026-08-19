package io.cassyx.licensing.config;

import io.cassyx.license.api.LicenseFactory;
import io.cassyx.license.api.LicenseVerifier;
import io.cassyx.license.api.PaymentProvider;
import io.cassyx.license.impl.stripe.StripeConfig;
import io.cassyx.license.impl.stripe.StripePaymentProvider;
import io.cassyx.licensing.email.LicenseEmailSender;
import io.cassyx.licensing.email.LoggingLicenseEmailSender;
import io.cassyx.licensing.mint.Ed25519LicenseMinter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring for the licensing service. Unlike cassyx-api this module may talk to
 * {@code io.cassyx.license.impl} directly: it is not part of the modular reactor and it exists
 * precisely to do the things the shipped product must not.
 */
@Configuration
public class LicensingConfiguration {

  private static final Logger LOG = LoggerFactory.getLogger(LicensingConfiguration.class);

  @Bean
  public Ed25519LicenseMinter licenseMinter(LicensingProperties properties) {
    String privateKey = properties.privateKey();
    if (privateKey == null || privateKey.isBlank() || privateKey.contains("PLACEHOLDER")) {
      // Fail fast and loud. A licensing service that starts without a private key looks healthy
      // and mints nothing, which is discovered only when a customer complains.
      throw new IllegalStateException(
          "CASSYX_LICENSING_PRIVATE_KEY is not set. Generate a pair with "
              + "io.cassyx.licensing.mint.KeyPairTool; the PUBLIC half goes to the product as "
              + "CASSYX_LICENSE_PUBLIC_KEY, the PRIVATE half stays here.");
    }
    return new Ed25519LicenseMinter(privateKey);
  }

  /** The very verifier the shipped product runs, so a minted key is checked before it is sent. */
  @Bean
  public LicenseVerifier licenseVerifier(LicensingProperties properties) {
    String publicKey = properties.publicKey();
    if (publicKey == null || publicKey.isBlank() || publicKey.contains("PLACEHOLDER")) {
      throw new IllegalStateException(
          "CASSYX_LICENSE_PUBLIC_KEY is not set. The service verifies everything it mints before "
              + "emailing it; without the public half that check cannot run.");
    }
    return LicenseFactory.verifier(publicKey);
  }

  @Bean
  public LicenseEmailSender licenseEmailSender(LicensingProperties properties) {
    LicensingProperties.Email email = properties.email();
    String provider = email == null || email.provider() == null ? "log" : email.provider();
    if (!"log".equalsIgnoreCase(provider)) {
      // Nothing else is implemented yet, and quietly falling back to the logging sender would mean
      // an operator who configured SMTP believes customers are being emailed when they are not.
      throw new IllegalStateException(
          "Unsupported CASSYX_LICENSING_EMAIL_PROVIDER '" + provider + "'. Implement "
              + "LicenseEmailSender for it; only 'log' ships today.");
    }
    LOG.warn(
        "Email provider is 'log': licence keys are written to this log, NOT emailed. "
            + "Fine for development; a production deployment must implement a real sender.");
    return new LoggingLicenseEmailSender(
        email == null ? "licensing@cassyx.dev" : email.from(),
        email == null || email.subject() == null ? "Your cassyx licence key" : email.subject());
  }

  /** Stripe, for webhooks delivered straight to this service rather than via the product. */
  @Bean
  public PaymentProvider paymentProvider(LicensingProperties properties) {
    LicensingProperties.Stripe stripe = properties.stripe();
    if (stripe == null) {
      return LicenseFactory.paymentProvider("noop");
    }
    return new StripePaymentProvider(
        new StripeConfig(stripe.secretKey(), stripe.webhookSecret(), stripe.apiBaseUrl(), null));
  }
}
