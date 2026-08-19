package io.cassyx.licensing.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import com.icegreen.greenmail.util.ServerSetupTest;
import io.cassyx.licensing.email.LicenseEmailSender.EmailDeliveryException;
import io.cassyx.licensing.email.LicenseEmailSender.Reason;
import io.cassyx.licensing.store.IssuedLicense;
import jakarta.mail.BodyPart;
import jakarta.mail.Message;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * The SMTP sender against a real in-process SMTP server (GreenMail).
 *
 * <p>Deliberately not a mocked {@code JavaMailSender}: a mock proves a method was called, which is
 * not the question. The question is whether a message that a customer paid for survives an actual
 * SMTP conversation with the licence key intact and copy-pasteable, and only a server can answer
 * that.
 */
class SmtpLicenseEmailSenderTest {

  private static final String KEY =
      "eyJsaWMiOiJDU1gtQUJDRC1FRkdILUlKS0wiLCJlbWFpbCI6Im9wc0BleGFtcGxlLmNvbSIsIm5hbWUiOiJFeGFtcGxl"
          + "IEdtYkgiLCJpc3N1ZWQiOiIyMDI2LTA4LTE4IiwiZWRpdGlvbiI6InN0YW5kYXJkIiwic2VhdHMiOjEsInZlciI6MX0"
          + ".Yl9zaWduYXR1cmVfYmFzZTY0dXJsX3RoYXRfaXNfZGVsaWJlcmF0ZWx5X2xvbmdfZW5vdWdoX3RvX3dyYXA";

  private static final String PASSWORD = "s3cret-smtp-password";

  private GreenMail greenMail;
  private ListAppender<ILoggingEvent> logs;
  private ch.qos.logback.classic.Logger rootLogger;

  @BeforeEach
  void startServer() {
    ServerSetup setup = ServerSetupTest.SMTP.dynamicPort();
    greenMail = new GreenMail(setup);
    greenMail.start();

    // Scoped to our own code on purpose. GreenMail itself logs the raw SMTP conversation at DEBUG,
    // which is a property of the test server, not of anything this service would ever emit.
    rootLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("io.cassyx");
    logs = new ListAppender<>();
    logs.start();
    rootLogger.addAppender(logs);
    rootLogger.setLevel(Level.DEBUG);
  }

  @AfterEach
  void stopServer() {
    rootLogger.detachAppender(logs);
    if (greenMail.isRunning()) {
      greenMail.stop();
    }
  }

  @Test
  void deliversTheKeyIntactAndCopyPasteableInBothBodyParts() throws Exception {
    sender(greenMail.getSmtp().getPort(), null, 3, Duration.ZERO)
        .send(license("standard", null), Reason.PURCHASE);

    assertThat(greenMail.waitForIncomingEmail(5000, 1)).isTrue();
    MimeMessage received = greenMail.getReceivedMessages()[0];

    assertThat(received.getRecipients(Message.RecipientType.TO)[0].toString())
        .isEqualTo("ops@example.com");
    assertThat(received.getFrom()[0].toString()).contains("licensing@example.com");
    assertThat(received.getReplyTo()[0].toString()).contains("support@example.com");
    // multipart/alternative, not a nested mixed/related wrapper: both parts, no attachments.
    assertThat(received.getContentType()).startsWith("multipart/alternative");

    String text = part(received, "text/plain");
    String html = part(received, "text/html");

    // The key must survive transport as ONE unbroken token in both parts. A key with a newline in
    // it fails signature verification, and the customer has no way to tell why.
    assertThat(text).contains(KEY);
    assertThat(html).contains(KEY);
    // ... and it must be on its own line with no leading whitespace, so a triple-click selects the
    // key and nothing else.
    assertThat(text.lines().filter(line -> line.equals(KEY)).count()).isEqualTo(1);
    // The HTML part must not let a narrow client insert a hard break into it.
    assertThat(html).contains("word-break:break-all");
  }

  @Test
  void saysSomethingDifferentAndUsefulForEachReason() throws Exception {
    SmtpLicenseEmailSender sender = sender(greenMail.getSmtp().getPort(), null, 3, Duration.ZERO);
    sender.send(license("standard", null), Reason.PURCHASE);
    sender.send(license("trial", LocalDate.of(2026, 9, 1)), Reason.TRIAL);
    sender.send(license("standard", null), Reason.RECOVERY);

    assertThat(greenMail.waitForIncomingEmail(5000, 3)).isTrue();
    MimeMessage[] received = greenMail.getReceivedMessages();
    String purchaseSubject = received[0].getSubject();
    String trialSubject = received[1].getSubject();
    String recoverySubject = received[2].getSubject();
    assertThat(List.of(purchaseSubject, trialSubject, recoverySubject)).doesNotHaveDuplicates();

    String purchase = part(received[0], "text/plain");
    assertThat(purchase).contains("Thank you for buying cassyx");
    assertThat(purchase).contains("standard").contains("CSX-ABCD-EFGH-IJKL");
    assertThat(purchase).contains("How to activate it").contains("CASSYX_LICENSE_KEY");
    assertThat(purchase).contains("If you lose this email").contains("https://cassyx.dev/recover");

    String trial = part(received[1], "text/plain");
    assertThat(trialSubject).containsIgnoringCase("trial");
    // The expiry date, stated plainly, and stated as inclusive - see plan section 9.4.
    assertThat(trial).contains("2026-09-01").contains("inclusive");
    assertThat(trial).contains("When you are ready to buy").contains("https://cassyx.dev/pricing");
    assertThat(part(received[1], "text/html")).contains("2026-09-01");

    String recovery = part(received[2], "text/plain");
    assertThat(recoverySubject).containsIgnoringCase("re-send");
    // Must not read as a second charge.
    assertThat(recovery).contains("RE-SEND of an existing key");
    assertThat(recovery).contains("you have not been charged");
    assertThat(recovery).doesNotContain("Thank you for buying");
  }

  @Test
  void throwsEmailDeliveryExceptionWhenSmtpIsUnreachable() {
    int deadPort = greenMail.getSmtp().getPort();
    greenMail.stop();

    assertThatThrownBy(
            () ->
                sender(deadPort, null, 2, Duration.ZERO)
                    .send(license("standard", null), Reason.PURCHASE))
        // Not swallowed and not some other type: LicensingService keys the whole undelivered/retry
        // path off this exact exception.
        .isInstanceOf(EmailDeliveryException.class);
  }

  @Test
  void retriesAreBoundedAndActuallyHappen() {
    int deadPort = greenMail.getSmtp().getPort();
    greenMail.stop();

    long startedAt = System.nanoTime();
    assertThatThrownBy(
            () ->
                sender(deadPort, null, 3, Duration.ofMillis(200))
                    .send(license("standard", null), Reason.PURCHASE))
        .isInstanceOf(EmailDeliveryException.class)
        .hasMessageContaining("after 3 attempts");
    long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

    // Two pauses between three attempts: proves the retries are real, and that they stopped.
    assertThat(elapsedMs).isGreaterThanOrEqualTo(400);
  }

  @Test
  void aPermanentRejectionIsNotRetried() {
    greenMail.setUser("relay@example.com", "relay", "the-right-password");
    int port = greenMail.getSmtp().getPort();

    long startedAt = System.nanoTime();
    assertThatThrownBy(
            () ->
                // A one-minute retry delay: if a permanent failure were retried at all, this test
                // would take a minute rather than failing the assertion below.
                sender(port, "wrong-password", 3, Duration.ofSeconds(60))
                    .send(license("standard", null), Reason.PURCHASE))
        .isInstanceOf(EmailDeliveryException.class)
        .hasMessageContaining("permanently rejected");
    long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

    assertThat(elapsedMs).isLessThan(10_000);
    assertThat(greenMail.getReceivedMessages()).isEmpty();
  }

  @Test
  void aMalformedRecipientFailsImmediatelyRatherThanLooping() {
    IssuedLicense broken =
        new IssuedLicense(
            "id-1",
            "CSX-ABCD-EFGH-IJKL",
            "bad@",
            "Example GmbH",
            "standard",
            1,
            1,
            LocalDate.of(2026, 8, 18),
            null,
            null,
            KEY,
            "evt_1",
            IssuedLicense.PENDING,
            0);

    assertThatThrownBy(
            () ->
                sender(greenMail.getSmtp().getPort(), null, 3, Duration.ofSeconds(60))
                    .send(broken, Reason.PURCHASE))
        .isInstanceOf(EmailDeliveryException.class)
        .hasMessageContaining("Could not compose");
  }

  @Test
  void neverLogsTheLicenceKeyOrTheSmtpPassword() {
    // Success path first...
    sender(greenMail.getSmtp().getPort(), null, 3, Duration.ZERO)
        .send(license("standard", null), Reason.PURCHASE);
    // ... then the failure path, which is where a careless "log the whole message" creeps in.
    greenMail.setUser("relay@example.com", "relay", "the-right-password");
    try {
      sender(greenMail.getSmtp().getPort(), PASSWORD, 2, Duration.ZERO)
          .send(license("standard", null), Reason.PURCHASE);
    } catch (EmailDeliveryException expected) {
      // The point of this test is what was logged on the way out, not the throw itself.
      assertThat(expected.getMessage()).doesNotContain(KEY).doesNotContain(PASSWORD);
    }

    String everything = String.join("\n", captured());
    assertThat(everything).doesNotContain(KEY);
    assertThat(everything).doesNotContain(PASSWORD);
    // Sanity check that the appender was actually wired up, so this test cannot pass vacuously.
    assertThat(everything).contains("ops@example.com");
  }

  private SmtpLicenseEmailSender sender(
      int port, String password, int maxAttempts, Duration retryDelay) {
    JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
    mailSender.setHost("127.0.0.1");
    mailSender.setPort(port);
    mailSender.setDefaultEncoding("UTF-8");
    if (password != null) {
      mailSender.setUsername("relay");
      mailSender.setPassword(password);
    }
    Properties props = mailSender.getJavaMailProperties();
    props.put("mail.transport.protocol", "smtp");
    props.put("mail.smtp.auth", String.valueOf(password != null));
    props.put("mail.smtp.connectiontimeout", "5000");
    props.put("mail.smtp.timeout", "5000");
    props.put("mail.smtp.writetimeout", "5000");
    return new SmtpLicenseEmailSender(
        mailSender,
        new LicenseEmailContent(
            "Your cassyx licence key",
            "https://cassyx.dev/pricing",
            "https://cassyx.dev/recover"),
        "licensing@example.com",
        "cassyx",
        "support@example.com",
        maxAttempts,
        retryDelay);
  }

  private static IssuedLicense license(String edition, LocalDate expiresOn) {
    return new IssuedLicense(
        "id-1",
        "CSX-ABCD-EFGH-IJKL",
        "ops@example.com",
        "Example GmbH",
        edition,
        1,
        1,
        LocalDate.of(2026, 8, 18),
        expiresOn,
        null,
        KEY,
        "evt_1",
        IssuedLicense.PENDING,
        0);
  }

  /** Formatted messages plus every throwable message attached to them. */
  private List<String> captured() {
    List<String> out = new ArrayList<>();
    for (ILoggingEvent event : logs.list) {
      out.add(event.getFormattedMessage());
      for (IThrowableProxy proxy = event.getThrowableProxy();
          proxy != null;
          proxy = proxy.getCause()) {
        out.add(proxy.getClassName() + ": " + proxy.getMessage());
      }
    }
    return out;
  }

  private static String part(MimeMessage message, String mimeType) throws Exception {
    MimeMultipart multipart = (MimeMultipart) message.getContent();
    for (int i = 0; i < multipart.getCount(); i++) {
      BodyPart part = multipart.getBodyPart(i);
      if (part.isMimeType(mimeType)) {
        return (String) part.getContent();
      }
    }
    throw new AssertionError("no " + mimeType + " part in the message");
  }
}
