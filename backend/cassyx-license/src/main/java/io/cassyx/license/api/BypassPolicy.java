package io.cassyx.license.api;

/**
 * Whether {@code cassyx.license.enforce=false} (plan section 9.2) actually unlocks anything.
 *
 * <p>The free switch is documented, so anyone who reads the README can flip it - and the code
 * cannot tell the maintainer's CI from a customer who read the docs. It still has legitimate uses
 * (development, CI, evaluation, enterprise site deployments), so it is not deleted: it is gated at
 * BUILD time by {@code cassyx.license.bypass-allowed}, which the {@code release} Maven profile
 * bakes to false in the published image. The legitimate users get a signed
 * {@link License#SITE_EDITION} licence instead, which needs no switch.
 *
 * <p>When the switch is set but the build refuses it, enforcement simply stays on. That refusal
 * must be LOUD: an operator who set the env var and saw nothing happen would otherwise conclude
 * the product is broken rather than that the flag is inert here.
 */
public record BypassPolicy(boolean requested, boolean allowed) {

  /** Named in every refusal message, because that is the string the operator actually set. */
  public static final String ENFORCE_ENV_VAR = "CASSYX_LICENSE_ENFORCE";

  /** Named in every refusal message so the reason is greppable in this repo and in the docs. */
  public static final String BYPASS_ALLOWED_PROPERTY = "cassyx.license.bypass-allowed";

  public static BypassPolicy of(boolean enforce, boolean bypassAllowed) {
    return new BypassPolicy(!enforce, bypassAllowed);
  }

  /** The bypass was asked for AND this build permits it: the product runs unlocked. */
  public boolean granted() {
    return requested && allowed;
  }

  /** The bypass was asked for and this build ignored it. Enforcement stays on. */
  public boolean refused() {
    return requested && !allowed;
  }

  /**
   * Whether licences are actually checked. This - not the raw {@code enforce} flag - is what the
   * API must report, or a release build would claim enforcement is off while it is verifying keys.
   */
  public boolean enforcing() {
    return !granted();
  }

  /** WARN text for a refused bypass; names the env var so the operator is never left guessing. */
  public String refusalWarning() {
    return ENFORCE_ENV_VAR
        + "=false was IGNORED: this is a release build ("
        + BYPASS_ALLOWED_PROPERTY
        + "=false), so licence enforcement stays ON. "
        + "To run unlocked, request a free site licence (edition '"
        + License.SITE_EDITION
        + "') for CI, evaluation or enterprise use and set CASSYX_LICENSE_KEY.";
  }
}
