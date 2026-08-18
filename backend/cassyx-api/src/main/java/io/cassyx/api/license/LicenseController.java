package io.cassyx.api.license;

import io.cassyx.license.api.License;
import io.cassyx.license.api.LicenseFactory;
import io.cassyx.license.api.LicenseState;
import io.cassyx.license.api.LicenseStatus;
import io.cassyx.license.api.LicenseVerifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/license} and {@code POST /api/license/activate} - two of the three ungated paths
 * (plan section 9.1). The UI calls the GET before rendering anything at all, so a missing
 * implementation here locks the whole product behind "Could not reach the cassyx API".
 *
 * <p>Response shape is the {@code LicenseStatus} schema in {@code openapi/cassyx-api.yaml}; per plan
 * section 2.3 the contract governs. Verification is entirely local (plan section 9.1) - no network
 * call - because self-hosted Cassandra clusters frequently have no egress.
 */
@RestController
public class LicenseController {

  private final LicenseVerifier verifier;
  private final String configuredKey;
  private final boolean enforce;
  private final int appMajor;

  public LicenseController(
      @Value("${cassyx.license.public-key:}") String publicKeyBase64,
      @Value("${cassyx.license.key:}") String configuredKey,
      @Value("${cassyx.license.enforce:true}") boolean enforce,
      @Value("${cassyx.version:0.1.0-SNAPSHOT}") String version) {
    // The shipped default for cassyx.license.public-key is the literal "PLACEHOLDER", which is not
    // a decodable Ed25519 key. Building the verifier eagerly therefore threw in the constructor and
    // took the entire application down at boot - so a fresh `make up` served nothing at all and the
    // UI reported "Could not reach the cassyx API".
    //
    // A placeholder credential is a configuration gap, not a fatal error: with enforce=false the
    // verifier is never consulted, and with enforce=true the honest answer is "no usable key", which
    // this endpoint must still be reachable to say.
    this.verifier = tryBuildVerifier(publicKeyBase64);
    this.configuredKey = configuredKey;
    this.enforce = enforce;
    this.appMajor = majorOf(version);
  }

  private static LicenseVerifier tryBuildVerifier(String publicKeyBase64) {
    if (publicKeyBase64 == null || publicKeyBase64.isBlank() || "PLACEHOLDER".equals(publicKeyBase64)) {
      return null;
    }
    try {
      return LicenseFactory.verifier(publicKeyBase64);
    } catch (RuntimeException e) {
      // Reported through the endpoint below rather than thrown, for the reason above.
      return null;
    }
  }

  @GetMapping("/api/license")
  public LicenseStatusResponse current() {
    return describe(check(configuredKey));
  }

  /**
   * Verification, tolerant of an unconfigured public key. When enforcement is off the bypass wins
   * outright (plan section 9.2); when it is on and no key is configured, we report {@code MALFORMED}
   * with an operator-facing reason rather than pretending the licence is merely absent - those are
   * different problems with different fixes.
   */
  private LicenseStatus check(String key) {
    if (!enforce) {
      return LicenseFactory.check(verifier, key, false);
    }
    if (verifier == null) {
      return LicenseStatus.invalid(
          "cassyx.license.public-key is not configured, so no licence can be verified. "
              + "Set CASSYX_LICENSE_PUBLIC_KEY, or set CASSYX_LICENSE_ENFORCE=false to run unlocked.",
          LicenseState.MALFORMED);
    }
    return LicenseFactory.check(verifier, key, true);
  }

  /**
   * Activating stores nothing yet - it verifies the supplied key and reports what it is. Persisting
   * the key is workstream H's job (plan section 10); returning a truthful verdict here is what lets
   * the activation screen work at all in the meantime.
   */
  @PostMapping("/api/license/activate")
  public ResponseEntity<LicenseStatusResponse> activate(@RequestBody ActivationRequest request) {
    String key = request == null ? null : request.licenseKey();
    LicenseStatusResponse body = describe(check(key));
    // A bad key is a normal, expected answer rather than a server fault, but it is still a rejected
    // activation - 422 lets the UI distinguish "we read your key and it is not valid" from "the
    // request itself was malformed".
    return body.licensed() ? ResponseEntity.ok(body) : ResponseEntity.unprocessableEntity().body(body);
  }

  private LicenseStatusResponse describe(LicenseStatus status) {
    License license = status.license();
    boolean bypass = status.state() == LicenseState.BYPASS || !enforce;
    return new LicenseStatusResponse(
        status.valid(),
        enforce,
        bypass,
        edition(license, bypass, status),
        status.state() == null ? null : status.state().name(),
        license == null || license.expires() == null ? null : license.expires().toString(),
        status.daysRemaining(),
        license != null && "trial".equalsIgnoreCase(license.edition()),
        license == null ? null : license.scope(),
        license == null ? null : license.lic(),
        license == null ? null : license.email(),
        license == null ? null : license.name(),
        license == null || license.issued() == null ? null : license.issued().toString(),
        license == null ? 0 : license.seats(),
        license == null ? 0 : license.ver(),
        status.reason());
  }

  private static String edition(License license, boolean bypass, LicenseStatus status) {
    if (bypass) {
      // Deliberately distinct from a real edition so the UI can keep the bypass banner visible and
      // a bypassed instance is never mistaken for a paid one (plan section 9.2).
      return "unlicensed-bypass";
    }
    if (license != null && license.edition() != null) {
      return license.edition();
    }
    return status.valid() ? "standard" : "none";
  }

  /** {@code 0.1.0-SNAPSHOT} -> {@code 0}. Falls back to 0, which the verifier treats as unscoped. */
  private static int majorOf(String version) {
    if (version == null || version.isBlank()) {
      return 0;
    }
    try {
      return Integer.parseInt(version.split("[.\\-+]")[0]);
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  /** Mirrors the contract's {@code LicenseActivationRequest}. */
  public record ActivationRequest(String licenseKey) {}

  /** Mirrors the contract's {@code LicenseStatus}. */
  public record LicenseStatusResponse(
      boolean licensed,
      boolean enforce,
      boolean bypass,
      String edition,
      String state,
      String expires,
      Long daysRemaining,
      boolean trial,
      Integer scope,
      String licenseId,
      String email,
      String name,
      String issued,
      int seats,
      int payloadVersion,
      String message) {}
}
