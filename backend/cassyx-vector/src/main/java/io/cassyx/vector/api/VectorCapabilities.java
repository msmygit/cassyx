package io.cassyx.vector.api;

import io.cassyx.core.api.Capability;
import io.cassyx.core.api.ClusterCapabilities;

/**
 * Whether this cluster can do vector/ANN and SAI at all (plan section 7.1).
 *
 * <p>Vector/ANN exists on Cassandra 5.x and Astra only; SAI additionally on DSE 6.8+. NEITHER
 * exists on Amazon Keyspaces or ScyllaDB. Unsupported features are hidden behind an explanatory
 * tooltip, never shown broken - {@link #explain} is that tooltip's text.
 */
public record VectorCapabilities(boolean vectorAnn, boolean sai, String flavor, String version) {

  public VectorCapabilities {
    flavor = flavor == null ? "UNKNOWN" : flavor;
    version = version == null ? "unknown" : version;
  }

  /** Derives the two gates from a core capability probe result. */
  public static VectorCapabilities from(ClusterCapabilities capabilities) {
    if (capabilities == null) {
      return new VectorCapabilities(false, false, "UNKNOWN", "unknown");
    }
    return new VectorCapabilities(
        capabilities.supports(Capability.VECTOR_ANN),
        capabilities.supports(Capability.SAI),
        capabilities.flavor().name(),
        capabilities.versionString());
  }

  /** Everything allowed - for tests and for the plain-Java embedding path with no probe. */
  public static VectorCapabilities permissive() {
    return new VectorCapabilities(true, true, "UNKNOWN", "unknown");
  }

  /** Human-readable reason a feature is hidden, for the UI tooltip and the 501 problem detail. */
  public String explain(Capability capability) {
    String requirement =
        capability == Capability.VECTOR_ANN
            ? "Vector columns and ANN queries require Cassandra 5.x or Astra."
            : "SAI indexes require Cassandra 5.x, DSE 6.8+ or Astra.";
    return requirement + " This cluster reports " + flavor + " " + version + ".";
  }

  public boolean supports(Capability capability) {
    return capability == Capability.VECTOR_ANN ? vectorAnn : capability == Capability.SAI && sai;
  }
}
