package io.cassyx.license.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cassyx.license.api.License;
import io.cassyx.license.api.LicenseState;
import io.cassyx.license.api.LicenseStatus;
import io.cassyx.license.api.LicenseVerifier;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Objects;

/**
 * Verifies {@code base64url(payloadJson).base64url(signature)} against an embedded Ed25519 PUBLIC
 * key, then enforces {@code expires} if present. No network call on the hot path (plan section 9.1).
 *
 * <p>Order matters: the signature is checked <em>before</em> the expiry date, because {@code
 * expires} is only trustworthy once we know the payload was not edited. Checking expiry first would
 * let anyone extend their own trial by editing a date.
 *
 * <p>The clock is UTC by default rather than the host's zone, so a licence does not lapse at a
 * different instant depending on where the container happens to run.
 */
public final class Ed25519LicenseVerifier implements LicenseVerifier {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** Applied when no application major version is supplied: scope checking is then a no-op. */
  public static final int UNSCOPED = Integer.MIN_VALUE;

  private final PublicKey publicKey;
  private final Clock clock;
  private final int appMajor;

  /** @param publicKeyBase64 X.509 SubjectPublicKeyInfo, base64 - {@code CASSYX_LICENSE_PUBLIC_KEY} */
  public Ed25519LicenseVerifier(String publicKeyBase64) {
    this(decodePublicKey(Objects.requireNonNull(publicKeyBase64, "publicKeyBase64")),
        Clock.system(ZoneOffset.UTC), UNSCOPED);
  }

  public Ed25519LicenseVerifier(PublicKey publicKey) {
    this(publicKey, Clock.system(ZoneOffset.UTC), UNSCOPED);
  }

  /** Clock-injecting constructor - trial expiry is untestable without it. */
  public Ed25519LicenseVerifier(PublicKey publicKey, Clock clock) {
    this(publicKey, clock, UNSCOPED);
  }

  /**
   * @param appMajor the running application's major version, for {@code scope} checks (plan 9.5), or
   *     {@link #UNSCOPED} to skip them
   */
  public Ed25519LicenseVerifier(PublicKey publicKey, Clock clock, int appMajor) {
    this.publicKey = Objects.requireNonNull(publicKey, "publicKey");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.appMajor = appMajor;
  }

  /** Visible for testing / for the licensing service. */
  public static PublicKey decodePublicKey(String base64) {
    try {
      byte[] der = Base64.getDecoder().decode(base64.trim());
      return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      throw new IllegalArgumentException("Invalid Ed25519 public key", e);
    }
  }

  @Override
  public LicenseStatus verify(String licenseKey) {
    if (licenseKey == null || licenseKey.isBlank()) {
      return LicenseStatus.invalid("No license key configured", LicenseState.ABSENT);
    }
    String[] parts = licenseKey.trim().split("\\.");
    if (parts.length != 2) {
      return LicenseStatus.invalid("Malformed license key", LicenseState.MALFORMED);
    }
    byte[] payload;
    byte[] signature;
    try {
      payload = Base64.getUrlDecoder().decode(parts[0]);
      signature = Base64.getUrlDecoder().decode(parts[1]);
    } catch (IllegalArgumentException e) {
      return LicenseStatus.invalid("Malformed license key encoding", LicenseState.MALFORMED);
    }
    try {
      Signature verifier = Signature.getInstance("Ed25519");
      verifier.initVerify(publicKey);
      verifier.update(payload);
      if (!verifier.verify(signature)) {
        return LicenseStatus.invalid("License signature does not match");
      }
    } catch (GeneralSecurityException e) {
      return LicenseStatus.invalid("License signature could not be checked");
    }
    License license;
    try {
      license = parsePayload(new String(payload, StandardCharsets.UTF_8));
    } catch (RuntimeException e) {
      return LicenseStatus.invalid("License payload is not readable", LicenseState.MALFORMED);
    }
    // Signature is now trusted, so `expires` and `scope` can be believed.
    LocalDate today = LocalDate.now(clock);
    if (license.isExpiredOn(today)) {
      return LicenseStatus.expired(
          license,
          (license.isTrial() ? "Trial expired on " : "License expired on ") + license.expires());
    }
    if (appMajor != UNSCOPED && !license.coversMajor(appMajor)) {
      return LicenseStatus.upgradeRequired(
          license,
          "License covers cassyx "
              + license.scope()
              + ".x; this build is "
              + appMajor
              + ".x. Upgrade the license or run the version you purchased.");
    }
    return LicenseStatus.valid(license, license.daysRemaining(today));
  }

  /** Visible for testing. */
  public static License parsePayload(String json) {
    try {
      JsonNode node = MAPPER.readTree(json);
      return new License(
          node.path("lic").asText(null),
          node.path("email").asText(null),
          node.path("name").asText(null),
          parseDate(node.path("issued").asText(null)),
          node.path("edition").asText(License.STANDARD_EDITION),
          node.path("seats").asInt(1),
          node.path("ver").asInt(1),
          // Absent on every perpetual key, including every key minted before trials existed.
          parseExpiry(node.path("expires").asText(null)),
          node.hasNonNull("scope") ? node.path("scope").asInt() : null);
    } catch (Exception e) {
      throw new IllegalArgumentException("Unreadable license payload", e);
    }
  }

  /**
   * Strict counterpart to {@link #parseDate}: absent means perpetual, but an <em>unparseable</em>
   * expiry must fail closed. Falling back to null here would silently upgrade a malformed trial into
   * a perpetual licence - the one direction this code must never get wrong.
   */
  private static LocalDate parseExpiry(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return LocalDate.parse(value);
    } catch (DateTimeParseException e) {
      throw new IllegalArgumentException("Unreadable 'expires' in license payload", e);
    }
  }

  private static LocalDate parseDate(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return LocalDate.parse(value);
    } catch (DateTimeParseException e) {
      return null;
    }
  }
}
