package io.cassyx.licensing.email;

import io.cassyx.licensing.store.IssuedLicense;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The default sender: writes the whole email to the log instead of sending it.
 *
 * <p>This is what makes {@code make up} of the licensing service work with no provider account, no
 * API key and no egress - a developer can complete a full sandbox purchase and read the licence key
 * straight out of the log. It logs at WARN, not INFO, because an operator who leaves this
 * configured in production is silently not emailing customers.
 */
public class LoggingLicenseEmailSender implements LicenseEmailSender {

  private static final Logger LOG = LoggerFactory.getLogger(LoggingLicenseEmailSender.class);

  private final String from;
  private final String subject;

  public LoggingLicenseEmailSender(String from, String subject) {
    this.from = from;
    this.subject = subject;
  }

  @Override
  public void send(IssuedLicense license, Reason reason) {
    LOG.warn(
        """
        EMAIL NOT SENT (provider=log). Copy the key from here.
          from:    {}
          to:      {}
          subject: {}
          reason:  {}
          licence: {} ({}{})
          key:     {}
        """,
        from,
        license.email(),
        subject,
        reason,
        license.licCode(),
        license.edition(),
        license.expiresOn() == null ? ", perpetual" : ", expires " + license.expiresOn(),
        license.licenseKey());
  }
}
