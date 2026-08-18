package io.cassyx.core.api;

/**
 * How well a {@link Capability} is supported by the probed cluster (plan section 7.1).
 *
 * <p>The distinction between {@link #PARTIAL} and {@link #UNSUPPORTED} is a product decision, not a
 * pedantic one: a partially supported feature is shown with its caveat, an unsupported one is
 * hidden behind an explanatory tooltip. Collapsing the two would either hide something that works
 * or show something that is broken.
 */
public enum CapabilitySupport {
  SUPPORTED,
  /** Usable with caveats - Astra roles, for instance. Shown, with the caveat. */
  PARTIAL,
  /** Hidden in the UI, with {@link CapabilityStatus#reason()} as the tooltip. */
  UNSUPPORTED,
  /** The probe could not tell. Treated as unsupported for gating, but worded differently. */
  UNKNOWN;

  /** Whether a feature may be offered at all. */
  public boolean usable() {
    return this == SUPPORTED || this == PARTIAL;
  }
}
