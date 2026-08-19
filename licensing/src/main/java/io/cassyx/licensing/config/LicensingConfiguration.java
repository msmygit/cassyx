package io.cassyx.licensing.config;

import io.cassyx.license.api.LicenseFactory;
import io.cassyx.license.api.LicenseVerifier;
import io.cassyx.license.api.PaymentProvider;
import io.cassyx.license.impl.stripe.StripeConfig;
import io.cassyx.license.impl.stripe.StripePaymentProvider;
import io.cassyx.licensing.email.LicenseEmailContent;
import io.cassyx.licensing.email.LicenseEmailSender;
import io.cassyx.licensing.email.LoggingLicenseEmailSender;
import io.cassyx.licensing.email.SmtpLicenseEmailSender;
import io.cassyx.licensing.mint.Ed25519LicenseMinter;
import java.time.Duration;
import java.util.Locale;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * Wiring for the licensing service. Unlike cassyx-api this module may talk to
 * {@code io.cassyx.license.impl} directly: it is not part of the modular reactor and it exists
 * precisely to do the things the shipped product must not.
 */
@Configuration
public class LicensingConfiguration {

  private static final Logger LOG = LoggerFactory.getLogger(LicensingConfiguration.class);

  private static final String DEFAULT_FROM = "licensing@cassyx.dev";
  private static final String DEFAULT_SUBJECT = "Your cassyx licence key";

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
    String subject =
        email == null || email.subject() == null ? DEFAULT_SUBJECT : email.subject();
    if ("log".equalsIgnoreCase(provider)) {
      LOG.warn(
          "Email provider is 'log': licence keys are written to this log, NOT emailed. "
              + "Fine for development; a production deployment must set "
              + "CASSYX_LICENSING_EMAIL_PROVIDER=smtp.");
      return new LoggingLicenseEmailSender(
          email == null ? DEFAULT_FROM : email.from(), subject);
    }
    if ("smtp".equalsIgnoreCase(provider)) {
      return smtpSender(email, subject);
    }
    // Deliberately no fallback. Quietly dropping back to the logging sender would mean an operator
    // who configured a provider believes customers are being emailed when they are not.
    throw new IllegalStateException(
        "Unsupported CASSYX_LICENSING_EMAIL_PROVIDER '" + provider + "'. Supported values are "
            + "'log' (development only) and 'smtp'.");
  }

  private LicenseEmailSender smtpSender(LicensingProperties.Email email, String subject) {
    LicensingProperties.Smtp smtp = email.smtp();
    if (smtp == null || smtp.host() == null || smtp.host().isBlank()) {
      throw new IllegalStateException(
          "CASSYX_LICENSING_EMAIL_PROVIDER=smtp needs CASSYX_LICENSING_SMTP_HOST. Starting "
              + "without it would mint licences nobody receives.");
    }
    if (email.from() == null || email.from().isBlank()) {
      throw new IllegalStateException(
          "CASSYX_LICENSING_EMAIL_FROM is required with the smtp provider, and must be on a domain "
              + "you control - see the deliverability section of licensing/README.md.");
    }
    LOG.info(
        "Email provider is 'smtp': host {}:{} tls={} from {} (timeouts {}/{}/{} ms, {} attempts)",
        smtp.host(),
        smtp.port(),
        smtp.tls(),
        email.from(),
        smtp.connectionTimeoutMs(),
        smtp.readTimeoutMs(),
        smtp.writeTimeoutMs(),
        Math.max(1, smtp.maxAttempts()));
    return new SmtpLicenseEmailSender(
        javaMailSender(smtp),
        new LicenseEmailContent(subject, email.purchaseUrl(), email.recoveryUrl()),
        email.from(),
        email.fromName(),
        email.replyTo(),
        smtp.maxAttempts(),
        Duration.ofMillis(Math.max(0, smtp.retryDelayMs())));
  }

  /**
   * Built here rather than through {@code spring.mail.*} autoconfiguration so that the provider
   * switch above is the single place email transport is decided; two sources of SMTP config is how
   * an operator ends up with a JavaMailSender pointed somewhere nobody intended.
   */
  private JavaMailSender javaMailSender(LicensingProperties.Smtp smtp) {
    JavaMailSenderImpl sender = new JavaMailSenderImpl();
    sender.setHost(smtp.host());
    sender.setPort(smtp.port() > 0 ? smtp.port() : 587);
    sender.setDefaultEncoding("UTF-8");
    if (smtp.username() != null && !smtp.username().isBlank()) {
      sender.setUsername(smtp.username());
      sender.setPassword(smtp.password());
    }
    Properties props = sender.getJavaMailProperties();
    props.put("mail.transport.protocol", "smtp");
    props.put("mail.smtp.auth", String.valueOf(smtp.username() != null && !smtp.username().isBlank()));
    // Jakarta Mail's debug output dumps the entire SMTP conversation, licence key included, to
    // stdout. It is off by default; pinned off here so it cannot be turned on by a stray property.
    props.put("mail.debug", "false");
    String tls = smtp.tls() == null ? "starttls" : smtp.tls().trim().toLowerCase(Locale.ROOT);
    switch (tls) {
      case "ssl", "implicit", "smtps" -> props.put("mail.smtp.ssl.enable", "true");
      case "none", "off", "plain" -> {
        // Local test servers only. Left silent rather than warned about because the only supported
        // way to reach it is an explicit env var that says exactly this.
      }
      case "starttls" -> {
        props.put("mail.smtp.starttls.enable", "true");
        // required, not just enable: without it a downgrade attack (or a misconfigured relay)
        // silently sends the customer's licence key in clear text.
        props.put("mail.smtp.starttls.required", "true");
      }
      default ->
          throw new IllegalStateException(
              "Unsupported CASSYX_LICENSING_SMTP_TLS '" + smtp.tls()
                  + "'. Use 'starttls' (port 587), 'ssl' (port 465) or 'none' (local testing).");
    }
    // Jakarta Mail defaults all three to infinite. A hung provider must fail fulfilment, not hang
    // it: the licence is already persisted, so a thrown failure is recoverable and a stall is not.
    props.put("mail.smtp.connectiontimeout", String.valueOf(positive(smtp.connectionTimeoutMs(), 10_000)));
    props.put("mail.smtp.timeout", String.valueOf(positive(smtp.readTimeoutMs(), 15_000)));
    props.put("mail.smtp.writetimeout", String.valueOf(positive(smtp.writeTimeoutMs(), 15_000)));
    return sender;
  }

  private static int positive(int value, int fallback) {
    return value > 0 ? value : fallback;
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
