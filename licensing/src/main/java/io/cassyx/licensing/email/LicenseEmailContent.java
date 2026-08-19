package io.cassyx.licensing.email;

import io.cassyx.licensing.email.LicenseEmailSender.Reason;
import io.cassyx.licensing.store.IssuedLicense;

/**
 * Renders the subject and both body parts of a licence email.
 *
 * <p>Separated from the transport so the wording can be tested without an SMTP conversation, and so
 * a second transport (should one ever be needed) cannot drift into a second set of words.
 *
 * <p>Two rules run through everything here. First, the key is the thing the customer paid for, so
 * it is printed alone on its own unindented line between two marker lines: leading whitespace would
 * be selected along with the key on a triple-click, and a key with a stray space in it fails
 * verification with no clue as to why. The HTML part wraps it in {@code word-break: break-all}
 * rather than letting a narrow client add a hard newline. Second, the three reasons say genuinely
 * different things - a recovery that reads like a purchase confirmation looks like a second charge.
 */
public final class LicenseEmailContent {

  /** Delimiters, not decoration: they tell the reader exactly where the key starts and ends. */
  private static final String KEY_OPEN = "----- BEGIN CASSYX LICENCE KEY -----";
  private static final String KEY_CLOSE = "----- END CASSYX LICENCE KEY -----";

  private final String baseSubject;
  private final String purchaseUrl;
  private final String recoveryUrl;

  public LicenseEmailContent(String baseSubject, String purchaseUrl, String recoveryUrl) {
    this.baseSubject = blankToNull(baseSubject) == null ? "Your cassyx licence key" : baseSubject;
    this.purchaseUrl = blankToNull(purchaseUrl);
    this.recoveryUrl = blankToNull(recoveryUrl);
  }

  /** Subject, plain text and HTML for one licence. */
  public record Rendered(String subject, String text, String html) {}

  public Rendered render(IssuedLicense license, Reason reason) {
    return new Rendered(subject(reason), text(license, reason), html(license, reason));
  }

  /**
   * Derived from the operator's configured subject rather than replacing it, so customising the
   * subject does not silently make a recovery indistinguishable from a purchase in the inbox list.
   */
  private String subject(Reason reason) {
    return switch (reason) {
      case PURCHASE -> baseSubject;
      case TRIAL -> baseSubject + " (trial)";
      case RECOVERY -> baseSubject + " (re-send of your existing key)";
    };
  }

  private String text(IssuedLicense license, Reason reason) {
    StringBuilder out = new StringBuilder();
    out.append(
        switch (reason) {
          case PURCHASE ->
              """
              Thank you for buying cassyx.

              Your licence key is below. Copy the whole line, including the dot in the middle.
              """;
          case TRIAL ->
              """
              Your cassyx trial is ready.

              Your trial licence key is below. Copy the whole line, including the dot in the middle.
              """;
          case RECOVERY ->
              """
              Here is the cassyx licence key you already hold, sent again at your request.

              This is a RE-SEND of an existing key, not a new purchase: you have not been charged
              again, and no new licence has been issued. The key below is the same one you had.
              """;
        });

    out.append('\n').append(KEY_OPEN).append('\n');
    out.append(license.licenseKey()).append('\n');
    out.append(KEY_CLOSE).append("\n\n");

    out.append(reason == Reason.TRIAL ? "Your trial\n" : "Your licence\n");
    out.append("  Licence ID:  ").append(license.licCode()).append('\n');
    out.append("  Edition:     ").append(license.edition()).append('\n');
    if (license.seats() > 0) {
      out.append("  Seats:       ").append(license.seats()).append('\n');
    }
    if (license.scope() != null) {
      out.append("  Covers:      cassyx ")
          .append(license.scope())
          .append(".x and every earlier major version\n");
    }
    out.append("  Issued:      ").append(license.issuedOn()).append('\n');
    if (license.expiresOn() == null) {
      out.append("  Expires:     never (perpetual)\n");
    } else {
      // Inclusive expiry (plan section 9.4). Saying "expires on X" while the key stopped working
      // on X reads as the product cheating the buyer out of a day, so spell the rule out.
      out.append("  Expires:     ")
          .append(license.expiresOn())
          .append(" (inclusive - it works for the whole of that day)\n");
    }

    out.append(
        """

        How to activate it
          1. Open cassyx. Any locked screen links to the activation page.
          2. Paste the key above and press Activate.

        Headless or air-gapped? Set CASSYX_LICENSE_KEY to the key above in the server's
        environment and restart. Activation is offline: cassyx verifies the signature locally
        and never calls out, so this works in a network with no egress.
        """);

    if (reason == Reason.TRIAL && purchaseUrl != null) {
      out.append("\nWhen you are ready to buy\n  ").append(purchaseUrl).append('\n');
    }
    if (reason != Reason.TRIAL) {
      out.append("\nIf you lose this email\n");
      if (recoveryUrl != null) {
        out.append("  Request the key again at ").append(recoveryUrl).append('\n');
      }
      out.append("  or simply reply to this message.\n");
    }
    out.append("\n-- cassyx\n");
    return out.toString();
  }

  private String html(IssuedLicense license, Reason reason) {
    StringBuilder out = new StringBuilder();
    out.append(
        """
        <!DOCTYPE html>
        <html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1"></head>
        <body style="margin:0;padding:24px;background:#f6f7f9;\
        font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;color:#1b1f24;">
        <div style="max-width:640px;margin:0 auto;background:#ffffff;border-radius:8px;\
        padding:28px;">
        """);

    out.append(
        switch (reason) {
          case PURCHASE ->
              """
              <h1 style="font-size:20px;margin:0 0 16px;">Thank you for buying cassyx</h1>
              <p style="margin:0 0 16px;">Your licence key is below. Copy the whole thing,
              including the dot in the middle.</p>
              """;
          case TRIAL ->
              """
              <h1 style="font-size:20px;margin:0 0 16px;">Your cassyx trial is ready</h1>
              <p style="margin:0 0 16px;">Your trial licence key is below. Copy the whole thing,
              including the dot in the middle.</p>
              """;
          case RECOVERY ->
              """
              <h1 style="font-size:20px;margin:0 0 16px;">Your cassyx licence key, again</h1>
              <p style="margin:0 0 16px;">This is a <strong>re-send of a key you already
              hold</strong>, not a new purchase. You have not been charged again and no new
              licence has been issued.</p>
              """;
        });

    // white-space:pre-wrap plus word-break:break-all is what stops a narrow client inserting a
    // hard line break into the key; the marker lines match the plain-text part so a reader who
    // sees either version is told the same thing about where the key ends.
    out.append("<p style=\"margin:0 0 4px;font-size:12px;color:#57606a;\">")
        .append(escape(KEY_OPEN))
        .append("</p>\n");
    out.append(
            "<pre style=\"margin:0;padding:14px;background:#f6f8fa;border:1px solid #d0d7de;"
                + "border-radius:6px;font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,"
                + "monospace;font-size:13px;line-height:1.5;white-space:pre-wrap;"
                + "word-break:break-all;overflow-wrap:break-word;\">")
        .append(escape(license.licenseKey()))
        .append("</pre>\n");
    out.append("<p style=\"margin:4px 0 20px;font-size:12px;color:#57606a;\">")
        .append(escape(KEY_CLOSE))
        .append("</p>\n");

    out.append("<h2 style=\"font-size:15px;margin:0 0 8px;\">")
        .append(reason == Reason.TRIAL ? "Your trial" : "Your licence")
        .append("</h2>\n<table style=\"border-collapse:collapse;font-size:14px;\">\n");
    row(out, "Licence ID", license.licCode());
    row(out, "Edition", license.edition());
    if (license.seats() > 0) {
      row(out, "Seats", String.valueOf(license.seats()));
    }
    if (license.scope() != null) {
      row(out, "Covers", "cassyx " + license.scope() + ".x and every earlier major version");
    }
    row(out, "Issued", String.valueOf(license.issuedOn()));
    row(
        out,
        "Expires",
        license.expiresOn() == null
            ? "never (perpetual)"
            : license.expiresOn() + " (inclusive - it works for the whole of that day)");
    out.append("</table>\n");

    out.append(
        """
        <h2 style="font-size:15px;margin:20px 0 8px;">How to activate it</h2>
        <ol style="margin:0 0 12px;padding-left:20px;font-size:14px;">
        <li>Open cassyx. Any locked screen links to the activation page.</li>
        <li>Paste the key above and press Activate.</li>
        </ol>
        <p style="margin:0 0 16px;font-size:14px;">Headless or air-gapped? Set
        <code>CASSYX_LICENSE_KEY</code> to the key above in the server's environment and restart.
        Activation is offline: cassyx verifies the signature locally and never calls out, so this
        works in a network with no egress.</p>
        """);

    if (reason == Reason.TRIAL && purchaseUrl != null) {
      out.append("<p style=\"margin:0 0 16px;font-size:14px;\">When you are ready to buy: ")
          .append(link(purchaseUrl))
          .append("</p>\n");
    }
    if (reason != Reason.TRIAL) {
      out.append("<p style=\"margin:0 0 16px;font-size:14px;\">If you lose this email, ");
      if (recoveryUrl != null) {
        out.append("request the key again at ").append(link(recoveryUrl)).append(" or ");
      }
      out.append("simply reply to this message.</p>\n");
    }

    out.append("<p style=\"margin:24px 0 0;font-size:12px;color:#57606a;\">-- cassyx</p>\n");
    out.append("</div></body></html>\n");
    return out.toString();
  }

  private static void row(StringBuilder out, String label, String value) {
    out.append("<tr><td style=\"padding:2px 16px 2px 0;color:#57606a;\">")
        .append(escape(label))
        .append("</td><td style=\"padding:2px 0;\">")
        .append(escape(value))
        .append("</td></tr>\n");
  }

  private static String link(String url) {
    return "<a href=\"" + escape(url) + "\">" + escape(url) + "</a>";
  }

  /**
   * The holder name and the edition originate outside this service (Stripe, or a trial request), so
   * they are attacker-influenced strings being pasted into markup.
   */
  private static String escape(String value) {
    if (value == null) {
      return "";
    }
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;");
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
