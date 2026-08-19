package io.cassyx.api.license;

import io.cassyx.api.config.CassyxVersion;
import io.cassyx.license.api.License;
import io.cassyx.license.api.LicenseState;
import io.cassyx.license.api.LicenseStatus;
import org.springframework.beans.factory.annotation.Autowired;
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
 *
 * <p>This controller decides NOTHING itself: the verdict comes from the shared {@link LicenseGate},
 * which is the same instance the request filter consults. That is deliberate - see {@link
 * LicenseGate} for why a second copy of this logic would be a bug rather than a duplication.
 */
@RestController
public class LicenseController {

  private final LicenseGate gate;

  @Autowired
  public LicenseController(LicenseGate gate) {
    this.gate = gate;
  }

  /**
   * Convenience form for tests and for callers holding raw configuration. Builds its own gate, so
   * it must never be used to wire the application: the filter would then hold a different instance
   * and the two could drift apart under a future change.
   */
  public LicenseController(
      String publicKeyBase64,
      String configuredKey,
      boolean enforce,
      boolean bypassAllowed,
      String version) {
    this(
        new LicenseGate(
            publicKeyBase64, configuredKey, enforce, bypassAllowed, CassyxVersion.of(version)));
  }

  @GetMapping("/api/license")
  public LicenseStatusResponse current() {
    return describe(gate.status());
  }

  /**
   * Activating stores nothing yet - it verifies the supplied key and reports what it is. Persisting
   * the key is workstream H's job (plan section 10); returning a truthful verdict here is what lets
   * the activation screen work at all in the meantime.
   */
  @PostMapping("/api/license/activate")
  public ResponseEntity<LicenseStatusResponse> activate(@RequestBody ActivationRequest request) {
    String key = request == null ? null : request.licenseKey();
    LicenseStatusResponse body = describe(gate.check(key));
    if (body.licensed()) {
      // Requirement of plan section 9.1's hot-path cache: the gate memoises its verdict, and a
      // customer who has just activated must not sit through the TTL watching 402s.
      gate.invalidate();
    }
    // A bad key is a normal, expected answer rather than a server fault, but it is still a rejected
    // activation - 422 lets the UI distinguish "we read your key and it is not valid" from "the
    // request itself was malformed".
    return body.licensed() ? ResponseEntity.ok(body) : ResponseEntity.unprocessableEntity().body(body);
  }

  private LicenseStatusResponse describe(LicenseStatus status) {
    License license = status.license();
    // A refused bypass must never be reported as a bypass: the flag was set, and nothing happened.
    // `enforce` is likewise the EFFECTIVE value, so the tuple (enforce, bypass, edition, state)
    // never contradicts itself - a client that trusts `enforce=false` would otherwise show an
    // "unlocked" UI over an instance that is verifying licences.
    boolean bypass = gate.policy().granted() || status.state() == LicenseState.BYPASS;
    return new LicenseStatusResponse(
        status.valid(),
        gate.policy().enforcing(),
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
