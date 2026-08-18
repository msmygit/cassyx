package io.cassyx.core.api;

import java.util.Set;

/**
 * Immutable result of capability detection at connect time. Unsupported features are hidden in the
 * UI with an explanation, never shown broken (plan section 7.1).
 */
public record ClusterCapabilities(
    ClusterFlavor flavor, String versionString, Set<Capability> capabilities) {

  public ClusterCapabilities {
    capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
  }

  public boolean supports(Capability capability) {
    return capabilities.contains(capability);
  }

  public static ClusterCapabilities unknown() {
    return new ClusterCapabilities(ClusterFlavor.UNKNOWN, "unknown", Set.of());
  }
}
