package io.cassyx.licensing.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code cassyx.licensing.*}. Every value is a placeholder in the repo; the real ones come from the
 * operator's environment (see {@code licensing/.env.example}).
 *
 * @param privateKey base64 PKCS#8 Ed25519 PRIVATE key. The whole reason this service exists.
 * @param publicKey base64 X.509 PUBLIC key, kept only so the service can self-check what it mints
 *     before emailing it - shipping a key that does not verify is unrecoverable support pain.
 * @param trialDays trial length; defaults to {@code License.DEFAULT_TRIAL_DAYS}
 * @param scope purchased major version written into {@code scope} (plan section 9.5); 0 means omit
 *     the field entirely, which is "unrestricted"
 * @param token shared secret expected in {@code X-Cassyx-Licensing-Token} on internal endpoints
 */
@ConfigurationProperties(prefix = "cassyx.licensing")
public record LicensingProperties(
    String privateKey,
    String publicKey,
    int trialDays,
    int scope,
    int payloadVersion,
    int seats,
    String token,
    Stripe stripe,
    Email email) {

  /** Stripe credentials for the webhook this service can receive directly. */
  public record Stripe(String secretKey, String webhookSecret, String apiBaseUrl) {}

  /**
   * @param provider {@code log} (default, no account needed) or {@code smtp}
   * @param from envelope sender used by real providers
   * @param fromName display name shown beside {@code from}; a bare address reads as machine spam
   * @param purchaseUrl where a trial recipient goes to buy; omitted from the body when blank
   * @param recoveryUrl where a customer re-requests a lost key; omitted from the body when blank
   */
  public record Email(
      String provider,
      String from,
      String fromName,
      String replyTo,
      String subject,
      String purchaseUrl,
      String recoveryUrl,
      Smtp smtp) {}

  /**
   * SMTP transport settings. Every mainstream provider (Postmark, SES, Resend, Mailgun, Fastmail,
   * Gmail) speaks SMTP, so these values are the whole of "which provider are we using".
   *
   * @param tls {@code starttls} (submission port 587, the usual choice), {@code ssl} (implicit TLS
   *     on 465) or {@code none} - the last one exists for a local test server and nothing else
   * @param connectionTimeoutMs bound on the TCP connect. Not optional: Jakarta Mail's default is
   *     infinite, and a hung SMTP socket inside the Stripe webhook handler stalls fulfilment for
   *     every buyer behind it, not just this one.
   * @param maxAttempts total attempts including the first; deliberately small, because retrying
   *     past a couple of tries is the recovery endpoint's job
   */
  public record Smtp(
      String host,
      int port,
      String username,
      String password,
      String tls,
      int connectionTimeoutMs,
      int readTimeoutMs,
      int writeTimeoutMs,
      int maxAttempts,
      long retryDelayMs) {}
}
