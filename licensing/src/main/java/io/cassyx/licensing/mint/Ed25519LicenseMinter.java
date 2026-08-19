package io.cassyx.licensing.mint;

import io.cassyx.license.api.License;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Base64;
import java.util.Objects;

/**
 * Mints {@code base64url(payloadJson).base64url(signature)} keys with the Ed25519 PRIVATE key (plan
 * section 9.1). The exact counterpart of {@code Ed25519LicenseVerifier}, and the only code in the
 * repository that touches a private key.
 *
 * <p>The payload is written by hand rather than through Jackson so the field set is literally the
 * one in the plan and nothing (a Jackson module, a null-inclusion setting, a field reordering) can
 * quietly change what gets signed. Optional fields are OMITTED when absent, never emitted as null:
 * absent {@code expires} means perpetual and absent {@code scope} means unrestricted, and
 * {@code "expires": null} is not the same statement.
 */
public final class Ed25519LicenseMinter {

  private static final SecureRandom RANDOM = new SecureRandom();

  /** Deliberately no I, O, 0 or 1: these codes get read aloud and retyped from support emails. */
  private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

  private static final int GROUP_LENGTH = 4;
  private static final int GROUPS = 3;

  private final PrivateKey privateKey;
  private final Clock clock;

  public Ed25519LicenseMinter(String privateKeyBase64) {
    this(decodePrivateKey(privateKeyBase64), Clock.systemUTC());
  }

  /** Clock-injecting constructor - trial expiry dates are untestable without it. */
  public Ed25519LicenseMinter(PrivateKey privateKey, Clock clock) {
    this.privateKey = Objects.requireNonNull(privateKey, "privateKey");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public static PrivateKey decodePrivateKey(String base64) {
    try {
      byte[] der = Base64.getDecoder().decode(base64.trim());
      return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(der));
    } catch (GeneralSecurityException | IllegalArgumentException | NullPointerException e) {
      throw new IllegalArgumentException("Invalid Ed25519 private key", e);
    }
  }

  public LocalDate today() {
    return LocalDate.now(clock);
  }

  /** {@code CSX-XXXX-XXXX-XXXX}, matching the contract's {@code licenseId} pattern. */
  public static String newLicenseCode() {
    StringBuilder code = new StringBuilder("CSX");
    for (int group = 0; group < GROUPS; group++) {
      code.append('-');
      for (int i = 0; i < GROUP_LENGTH; i++) {
        code.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
      }
    }
    return code.toString();
  }

  /** Signs the payload of {@code license} and returns the key as the customer receives it. */
  public String mint(License license) {
    byte[] payload = payloadJson(license).getBytes(StandardCharsets.UTF_8);
    byte[] signature = sign(payload);
    Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
    return encoder.encodeToString(payload) + "." + encoder.encodeToString(signature);
  }

  private byte[] sign(byte[] payload) {
    try {
      Signature signer = Signature.getInstance("Ed25519");
      signer.initSign(privateKey);
      signer.update(payload);
      return signer.sign();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Could not sign the licence payload", e);
    }
  }

  /** Visible for testing: the exact bytes that get signed. */
  static String payloadJson(License license) {
    StringBuilder json = new StringBuilder("{");
    field(json, "lic", license.lic(), true);
    field(json, "email", license.email(), false);
    field(json, "name", license.name(), false);
    field(json, "issued", String.valueOf(license.issued()), false);
    field(json, "edition", license.edition(), false);
    json.append(",\"seats\":").append(license.seats());
    json.append(",\"ver\":").append(license.ver());
    if (license.expires() != null) {
      field(json, "expires", license.expires().toString(), false);
    }
    if (license.scope() != null) {
      json.append(",\"scope\":").append(license.scope());
    }
    return json.append('}').toString();
  }

  private static void field(StringBuilder json, String name, String value, boolean first) {
    if (!first) {
      json.append(',');
    }
    json.append('"').append(name).append("\":").append(quote(value));
  }

  private static String quote(String value) {
    if (value == null) {
      return "null";
    }
    return "\""
        + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        + "\"";
  }
}
