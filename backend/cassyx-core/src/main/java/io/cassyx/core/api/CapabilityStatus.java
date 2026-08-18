package io.cassyx.core.api;

import java.util.Objects;

/**
 * One cell of the plan section 7.1 capability matrix.
 *
 * @param capability which feature
 * @param support how well it is supported
 * @param reason shown verbatim as the tooltip when the feature is hidden or caveated. Required in
 *     spirit for anything not {@link CapabilitySupport#SUPPORTED}: "SAI is unavailable" without a
 *     reason is a dead end for the user, "SAI requires Cassandra 5.x; this cluster reports 4.1.3"
 *     is actionable.
 * @param since the minimum version providing this capability on the detected flavour, or null
 */
public record CapabilityStatus(
    Capability capability, CapabilitySupport support, String reason, String since) {

  public CapabilityStatus {
    Objects.requireNonNull(capability, "capability");
    support = support == null ? CapabilitySupport.UNKNOWN : support;
    reason = blankToNull(reason);
    since = blankToNull(since);
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  public static CapabilityStatus supported(Capability capability) {
    return new CapabilityStatus(capability, CapabilitySupport.SUPPORTED, null, null);
  }

  public static CapabilityStatus supportedSince(Capability capability, String since) {
    return new CapabilityStatus(capability, CapabilitySupport.SUPPORTED, null, since);
  }

  public static CapabilityStatus partial(Capability capability, String reason) {
    return new CapabilityStatus(capability, CapabilitySupport.PARTIAL, reason, null);
  }

  public static CapabilityStatus unsupported(Capability capability, String reason) {
    return new CapabilityStatus(capability, CapabilitySupport.UNSUPPORTED, reason, null);
  }

  public static CapabilityStatus unknown(Capability capability, String reason) {
    return new CapabilityStatus(capability, CapabilitySupport.UNKNOWN, reason, null);
  }

  public boolean usable() {
    return support.usable();
  }
}
