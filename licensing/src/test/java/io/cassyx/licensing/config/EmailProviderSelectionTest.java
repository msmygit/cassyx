package io.cassyx.licensing.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.cassyx.licensing.email.LicenseEmailSender;
import io.cassyx.licensing.email.LoggingLicenseEmailSender;
import io.cassyx.licensing.email.SmtpLicenseEmailSender;
import org.junit.jupiter.api.Test;

/**
 * The provider switch itself. Its one non-negotiable property is that an unrecognised value fails
 * startup rather than falling back: a silent fallback to the logging sender is indistinguishable,
 * from the outside, from a service that is emailing every customer correctly.
 */
class EmailProviderSelectionTest {

  private final LicensingConfiguration configuration = new LicensingConfiguration();

  @Test
  void logIsTheDefaultAndStillWorks() {
    assertThat(configuration.licenseEmailSender(properties(email(null))))
        .isInstanceOf(LoggingLicenseEmailSender.class);
    assertThat(configuration.licenseEmailSender(properties(email("log"))))
        .isInstanceOf(LoggingLicenseEmailSender.class);
    assertThat(configuration.licenseEmailSender(properties(email("LOG"))))
        .isInstanceOf(LoggingLicenseEmailSender.class);
  }

  @Test
  void smtpIsSelectableAndDoesNotNeedAReachableServerToWireUp() {
    LicenseEmailSender sender = configuration.licenseEmailSender(properties(smtpEmail("starttls")));
    assertThat(sender).isInstanceOf(SmtpLicenseEmailSender.class);
  }

  @Test
  void everyTlsModeIsAccepted() {
    for (String tls : new String[] {"starttls", "ssl", "none"}) {
      assertThat(configuration.licenseEmailSender(properties(smtpEmail(tls))))
          .isInstanceOf(SmtpLicenseEmailSender.class);
    }
  }

  @Test
  void anUnknownProviderThrowsRatherThanFallingBackToLogging() {
    assertThatThrownBy(() -> configuration.licenseEmailSender(properties(email("postmark"))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("CASSYX_LICENSING_EMAIL_PROVIDER");
  }

  @Test
  void smtpWithoutAHostThrowsRatherThanStartingAndMintingKeysNobodyReceives() {
    LicensingProperties.Email email =
        new LicensingProperties.Email(
            "smtp", "licensing@example.com", "cassyx", null, "subject", null, null, null);
    assertThatThrownBy(() -> configuration.licenseEmailSender(properties(email)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("CASSYX_LICENSING_SMTP_HOST");
  }

  @Test
  void anUnknownTlsModeThrowsRatherThanSilentlySendingInClear() {
    assertThatThrownBy(() -> configuration.licenseEmailSender(properties(smtpEmail("maybe"))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("CASSYX_LICENSING_SMTP_TLS");
  }

  private static LicensingProperties.Email email(String provider) {
    return new LicensingProperties.Email(
        provider, "licensing@example.com", "cassyx", null, "Your cassyx licence key", null, null,
        null);
  }

  private static LicensingProperties.Email smtpEmail(String tls) {
    return new LicensingProperties.Email(
        "smtp",
        "licensing@example.com",
        "cassyx",
        "support@example.com",
        "Your cassyx licence key",
        "https://cassyx.dev/pricing",
        "https://cassyx.dev/recover",
        new LicensingProperties.Smtp(
            "smtp.example.com", 587, "relay", "hunter2", tls, 10_000, 15_000, 15_000, 3, 500));
  }

  private static LicensingProperties properties(LicensingProperties.Email email) {
    return new LicensingProperties(null, null, 14, 0, 1, 1, null, null, email);
  }
}
