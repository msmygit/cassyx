package io.cassyx.api.license;

import io.cassyx.api.config.CassyxVersion;
import io.cassyx.license.api.BypassPolicy;
import io.cassyx.license.api.LicenseFactory;
import io.cassyx.license.api.LicenseState;
import io.cassyx.license.api.LicenseStatus;
import io.cassyx.license.api.LicenseVerifier;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/**
 * THE licence decision (plan section 9.1) - one component, consulted by both the request filter
 * ({@code io.cassyx.api.filter.LicenseGateFilter}) and {@code GET /api/license}.
 *
 * <p>Sharing it is the whole point. If the gate and the status endpoint each derived their own
 * verdict from {@link BypassPolicy} plus the verifier, they could disagree, and both directions of
 * disagreement are bugs that cost real money: an instance that reports itself unlocked while
 * refusing every request looks broken to a paying customer, and one that reports itself locked
 * while serving every request is the product given away.
 *
 * <p>Verification is local and offline (plan section 9.1) but not free - it decodes an X.509 key,
 * base64url-decodes two blobs and runs an Ed25519 verify. The filter sits on every {@code /api/**}
 * call, so the verdict is cached; see {@link #TTL}.
 */
public class LicenseGate {

  /**
   * How long a verdict is reused. Not "forever": a licence can carry {@code expires} (trials, plan
   * section 9.4, and time-boxed evaluation site licences, section 9.2), so an unbounded cache would
   * keep a lapsed key working until the next restart - which for a long-lived self-hosted server is
   * effectively never. Thirty seconds bounds that to a rounding error while still collapsing a
   * burst of API calls onto one verification.
   */
  static final Duration TTL = Duration.ofSeconds(30);

  private final LicenseVerifier verifier;
  private final String configuredKey;
  private final BypassPolicy bypassPolicy;
  private final CassyxVersion version;
  private final Clock clock;
  private final AtomicReference<Cached> cache = new AtomicReference<>();

  /**
   * The ONLY public constructor, deliberately: Spring picks a sole public constructor without an
   * {@code @Autowired} hint, and a second one would break every {@code @WebMvcTest} slice that
   * imports this class. {@code @Autowired} is still required because the private constructor below
   * counts as a candidate, so Spring sees two and will not guess.
   */
  @Autowired
  public LicenseGate(
      @Value("${cassyx.license.public-key:}") String publicKeyBase64,
      @Value("${cassyx.license.key:}") String configuredKey,
      @Value("${cassyx.license.enforce:true}") boolean enforce,
      @Value("${cassyx.license.bypass-allowed:true}") boolean bypassAllowed,
      CassyxVersion version) {
    // The shipped default for cassyx.license.public-key is the literal "PLACEHOLDER", which is not
    // a decodable Ed25519 key. Building the verifier eagerly threw in the constructor and took the
    // entire application down at boot - so a fresh `make up` served nothing at all and the UI
    // reported "Could not reach the cassyx API".
    //
    // A placeholder credential is a configuration gap, not a fatal error, and a config gap must be
    // DIAGNOSABLE: /api/license is ungated, so it stays reachable and answers MALFORMED with an
    // operator-facing reason while the gate keeps refusing everything else.
    this(
        tryBuildVerifier(
            publicKeyBase64, (version == null ? CassyxVersion.of(null) : version).major()),
        configuredKey,
        // The build-time gate of plan section 9.2: in a release build enforce=false is inert, so
        // every answer derived here comes from the POLICY, never from the raw flag.
        BypassPolicy.of(enforce, bypassAllowed),
        version,
        Clock.system(ZoneOffset.UTC));
  }

  private LicenseGate(
      LicenseVerifier verifier,
      String configuredKey,
      BypassPolicy bypassPolicy,
      CassyxVersion version,
      Clock clock) {
    this.verifier = verifier;
    this.configuredKey = configuredKey;
    this.bypassPolicy = bypassPolicy;
    this.version = version == null ? CassyxVersion.of(null) : version;
    this.clock = clock;
  }

  /**
   * Verifier- and clock-injecting seam for tests. Neither the cache TTL nor "the verifier threw" is
   * testable without it, and both are parts that decide whether the product is given away. A static
   * factory rather than a constructor so this class keeps exactly one public constructor.
   */
  static LicenseGate forTesting(
      LicenseVerifier verifier,
      String configuredKey,
      BypassPolicy bypassPolicy,
      CassyxVersion version,
      Clock clock) {
    return new LicenseGate(verifier, configuredKey, bypassPolicy, version, clock);
  }

  private static LicenseVerifier tryBuildVerifier(String publicKeyBase64, int appMajor) {
    if (publicKeyBase64 == null
        || publicKeyBase64.isBlank()
        || publicKeyBase64.contains("PLACEHOLDER")) {
      return null;
    }
    try {
      return LicenseFactory.verifier(publicKeyBase64, appMajor);
    } catch (RuntimeException e) {
      // Reported through /api/license rather than thrown, for the reason above.
      return null;
    }
  }

  /**
   * The verdict for the configured key, cached for {@link #TTL}.
   *
   * <p>Fails CLOSED: any unexpected failure becomes an invalid status rather than an exception, so
   * a caller that forgets to catch cannot accidentally serve the product. The reason is kept
   * generic on purpose - it reaches an unauthenticated client.
   */
  public LicenseStatus status() {
    Instant now = clock.instant();
    Cached cached = cache.get();
    if (cached != null && now.isBefore(cached.expiresAt())) {
      return cached.status();
    }
    LicenseStatus fresh = check(configuredKey);
    cache.set(new Cached(fresh, now.plus(TTL)));
    return fresh;
  }

  /**
   * Drops the cached verdict. Called on activation: a customer who has just pasted a valid key must
   * not wait out a TTL staring at 402s.
   */
  public void invalidate() {
    cache.set(null);
  }

  /**
   * Verification of an arbitrary key, tolerant of an unconfigured public key. When the bypass is
   * granted it wins outright (plan section 9.2); when it is refused or never asked for and no public
   * key is configured, we report {@code MALFORMED} with an operator-facing reason rather than
   * pretending the licence is merely absent - those are different problems with different fixes.
   */
  public LicenseStatus check(String key) {
    try {
      if (bypassPolicy.granted()) {
        return LicenseFactory.check(verifier, key, false, true);
      }
      if (verifier == null) {
        return LicenseStatus.invalid(
            "cassyx.license.public-key is not configured, so no licence can be verified. "
                + "Set CASSYX_LICENSE_PUBLIC_KEY. "
                + unlockAdvice(),
            LicenseState.MALFORMED);
      }
      // Not granted, so enforcement is on whatever the flag said: verify for real.
      return LicenseFactory.check(verifier, key, true, bypassPolicy.allowed());
    } catch (RuntimeException e) {
      // Deny, never allow. A verifier that throws is a broken instance, and the safe reading of a
      // broken licence check is "not licensed" - the opposite default hands the product away on
      // any bug that reaches this line.
      return LicenseStatus.invalid("Licence verification failed", LicenseState.MALFORMED);
    }
  }

  public BypassPolicy policy() {
    return bypassPolicy;
  }

  public CassyxVersion version() {
    return version;
  }

  /**
   * The honest way out of a locked instance, which differs by build: a dev build still takes the
   * env flag, a release build does not and needs a signed site licence instead. Telling a release
   * operator to set a flag that this build ignores is worse than saying nothing.
   */
  public String unlockAdvice() {
    return bypassPolicy.allowed()
        ? "Or set CASSYX_LICENSE_ENFORCE=false to run unlocked (development builds only)."
        : "To run unlocked, request a free site licence (CI, evaluation, enterprise) and set "
            + "CASSYX_LICENSE_KEY; " + BypassPolicy.ENFORCE_ENV_VAR + " is ignored in this build.";
  }

  private record Cached(LicenseStatus status, Instant expiresAt) {}
}
