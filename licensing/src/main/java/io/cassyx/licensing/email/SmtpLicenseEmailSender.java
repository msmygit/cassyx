package io.cassyx.licensing.email;

import io.cassyx.licensing.store.IssuedLicense;
import jakarta.mail.Address;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.io.UnsupportedEncodingException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

/**
 * Delivers licence keys over SMTP.
 *
 * <p>SMTP rather than a vendor API SDK, deliberately. Postmark, SES, Resend, Mailgun, Fastmail and
 * Gmail all speak SMTP, so this one implementation covers every provider an operator might pick and
 * switching between them is an env-var change rather than a code change and a redeploy. It also
 * adds no vendor SDK - no HTTP client, no credential library, no transitive dependency tree - to
 * the one service in this repository that holds the Ed25519 private key. The cost is the features
 * only the APIs offer (per-message webhooks, template hosting, bounce callbacks), none of which
 * this service consumes: it sends one message per purchase and treats failure as "record it and let
 * the recovery endpoint retry".
 *
 * <p>Nothing here logs the licence key or the SMTP password. The {@code log} provider prints keys
 * because that is its entire purpose as a development tool; this path runs in production, where the
 * service log is the last place a customer's licence key should end up. What is logged is what is
 * needed to diagnose a failure - recipient, reason, licence code, attempt number and the provider's
 * own response - and nothing else, on the success path and the exception path alike.
 */
public class SmtpLicenseEmailSender implements LicenseEmailSender {

  private static final Logger LOG = LoggerFactory.getLogger(SmtpLicenseEmailSender.class);

  private final JavaMailSender mailSender;
  private final LicenseEmailContent content;
  private final String from;
  private final String fromName;
  private final String replyTo;
  private final int maxAttempts;
  private final Duration retryDelay;

  /**
   * @param maxAttempts total attempts including the first; clamped to at least 1
   * @param retryDelay pause between attempts. Short on purpose - this runs inside the Stripe
   *     webhook handler, and a long backoff here is a webhook timeout and a redelivery storm.
   */
  public SmtpLicenseEmailSender(
      JavaMailSender mailSender,
      LicenseEmailContent content,
      String from,
      String fromName,
      String replyTo,
      int maxAttempts,
      Duration retryDelay) {
    this.mailSender = mailSender;
    this.content = content;
    this.from = from;
    this.fromName = fromName;
    this.replyTo = replyTo;
    this.maxAttempts = Math.max(1, maxAttempts);
    this.retryDelay = retryDelay == null ? Duration.ZERO : retryDelay;
  }

  @Override
  public void send(IssuedLicense license, Reason reason) {
    MimeMessage message;
    try {
      message = compose(license, reason);
    } catch (MessagingException | UnsupportedEncodingException e) {
      // A malformed address or an unencodable header cannot become valid by being retried.
      throw new EmailDeliveryException(
          "Could not compose the licence email for " + license.licCode() + ": " + e.getMessage(), e);
    }

    MailException last = null;
    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
      try {
        mailSender.send(message);
        LOG.info(
            "Licence {} delivered to {} over SMTP (reason {}, attempt {} of {})",
            license.licCode(),
            license.email(),
            reason,
            attempt,
            maxAttempts);
        return;
      } catch (MailException e) {
        last = e;
        if (isPermanent(e)) {
          // Bad credentials or a rejected recipient. Retrying is guaranteed to fail again, and a
          // loop here would turn one misconfiguration into an unbounded pounding of the provider.
          LOG.error(
              "SMTP permanently rejected licence {} for {} (reason {}), not retrying: {}",
              license.licCode(),
              license.email(),
              reason,
              describe(e));
          throw new EmailDeliveryException(
              "SMTP permanently rejected the licence email for "
                  + license.email()
                  + " on attempt "
                  + attempt
                  + ": "
                  + describe(e),
              e);
        }
        LOG.warn(
            "SMTP delivery of licence {} to {} failed (reason {}, attempt {} of {}): {}",
            license.licCode(),
            license.email(),
            reason,
            attempt,
            maxAttempts,
            describe(e));
        if (attempt < maxAttempts) {
          pause();
        }
      }
    }

    // Bounded on purpose. Anything beyond a couple of quick retries belongs to
    // POST /licensing/recover, which already exists and is driven by a human who knows the provider
    // is back; throwing here is what makes LicensingService record the licence as undelivered.
    throw new EmailDeliveryException(
        "SMTP delivery of the licence email to "
            + license.email()
            + " failed after "
            + maxAttempts
            + " attempts: "
            + describe(last),
        last);
  }

  private MimeMessage compose(IssuedLicense license, Reason reason)
      throws MessagingException, UnsupportedEncodingException {
    LicenseEmailContent.Rendered rendered = content.render(license, reason);
    MimeMessage message = mailSender.createMimeMessage();
    // Headers through the helper, body by hand. MimeMessageHelper's multipart modes all nest the
    // alternative inside a mixed/related wrapper for attachments this message will never have, and
    // a bare multipart/alternative is what filters and text clients handle most predictably.
    MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
    if (fromName == null || fromName.isBlank()) {
      helper.setFrom(from);
    } else {
      helper.setFrom(from, fromName);
    }
    if (replyTo != null && !replyTo.isBlank()) {
      helper.setReplyTo(replyTo);
    }
    helper.setTo(license.email());
    helper.setSubject(rendered.subject());

    // Both parts, always. Text-only transactional mail looks broken to someone who just paid;
    // HTML-only hurts deliverability and renders as nothing in a text client.
    MimeMultipart alternative = new MimeMultipart("alternative");
    MimeBodyPart text = new MimeBodyPart();
    text.setText(rendered.text(), "UTF-8");
    MimeBodyPart html = new MimeBodyPart();
    html.setContent(rendered.html(), "text/html; charset=UTF-8");
    // Order is meaning in RFC 2046: least-preferred first, so the plain part must come first or a
    // client that understands both will show the fallback.
    alternative.addBodyPart(text);
    alternative.addBodyPart(html);
    message.setContent(alternative);
    return message;
  }

  private void pause() {
    if (retryDelay.isZero() || retryDelay.isNegative()) {
      return;
    }
    try {
      Thread.sleep(retryDelay.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new EmailDeliveryException("Interrupted while retrying SMTP delivery", e);
    }
  }

  /**
   * Permanent means "the same message to the same address will be refused again": bad credentials,
   * a rejected recipient, any 5xx SMTP reply. Everything else - refused connection, timeout, 4xx -
   * is treated as transient and retried, because the asymmetry matters: a wrongly-transient verdict
   * costs two wasted attempts, a wrongly-permanent one gives up on a customer who paid.
   */
  private static boolean isPermanent(MailException exception) {
    if (exception instanceof MailAuthenticationException
        || exception instanceof MailParseException) {
      return true;
    }
    for (Throwable candidate : unwrap(exception)) {
      if (candidate instanceof AddressException) {
        return true;
      }
      if (candidate instanceof SendFailedException failed) {
        Address[] invalid = failed.getInvalidAddresses();
        if (invalid != null && invalid.length > 0) {
          return true;
        }
      }
      if (isFivexxReply(candidate.getMessage())) {
        return true;
      }
    }
    return false;
  }

  /**
   * MailSendException keeps the real cause in {@code getFailedMessages()} rather than in
   * {@code getCause()}, so walking the cause chain alone misses the SMTP reply entirely.
   */
  private static List<Throwable> unwrap(MailException exception) {
    List<Throwable> roots = new ArrayList<>();
    roots.add(exception);
    if (exception instanceof MailSendException send) {
      roots.addAll(send.getFailedMessages().values());
      roots.addAll(List.of(send.getMessageExceptions()));
    }
    List<Throwable> all = new ArrayList<>();
    for (Throwable root : roots) {
      for (Throwable c = root; c != null && !all.contains(c); c = c.getCause()) {
        all.add(c);
      }
    }
    return all;
  }

  private static boolean isFivexxReply(String message) {
    if (message == null) {
      return false;
    }
    String trimmed = message.stripLeading();
    return trimmed.length() >= 3
        && trimmed.charAt(0) == '5'
        && Character.isDigit(trimmed.charAt(1))
        && Character.isDigit(trimmed.charAt(2));
  }

  /**
   * The provider's own words, which is what an operator needs. Exception messages from Jakarta Mail
   * carry the SMTP reply and the server host, never the message body and never the password, so
   * this is safe to log - but it is a single choke point precisely so that stays checkable.
   */
  private static String describe(MailException exception) {
    if (exception == null) {
      return "no detail";
    }
    StringBuilder out = new StringBuilder(exception.getClass().getSimpleName());
    for (Throwable candidate : unwrap(exception)) {
      String message = candidate.getMessage();
      if (message != null && !message.isBlank()) {
        out.append(": ").append(message.lines().findFirst().orElse(message));
        break;
      }
    }
    return out.toString();
  }
}
