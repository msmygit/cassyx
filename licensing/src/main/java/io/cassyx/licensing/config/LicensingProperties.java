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
   */
  public record Email(String provider, String from, String replyTo, String subject) {}
}
