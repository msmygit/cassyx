package io.cassyx.core.api;

import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The full result of the connect-time capability probe (plan section 7.1).
 *
 * <p>{@link ClusterCapabilities} is the small value feature code gates on; this is everything the
 * probe learned, and it is what the {@code ClusterCapabilities} response schema in the API contract
 * is built from. Two types rather than one because a bulk engine asking "can I do a token-range
 * scan?" should not have to hold a partitioner string and a probe timestamp.
 *
 * @param statuses one entry per {@link Capability}; a capability absent from the map is
 *     {@link CapabilitySupport#UNKNOWN}
 */
public record ClusterProbeResult(
    ClusterFlavor flavor,
    String clusterName,
    String releaseVersion,
    String dseVersion,
    String protocolVersion,
    String partitioner,
    int nodeCount,
    List<String> datacenters,
    Map<Capability, CapabilityStatus> statuses,
    Instant probedAt) {

  public ClusterProbeResult {
    flavor = flavor == null ? ClusterFlavor.UNKNOWN : flavor;
    datacenters = datacenters == null ? List.of() : List.copyOf(new LinkedHashSet<>(datacenters));
    statuses =
        statuses == null || statuses.isEmpty()
            ? Map.of()
            : Map.copyOf(new EnumMap<>(statuses));
    probedAt = probedAt == null ? Instant.EPOCH : probedAt;
  }

  public CapabilityStatus status(Capability capability) {
    Objects.requireNonNull(capability, "capability");
    return statuses.getOrDefault(
        capability, CapabilityStatus.unknown(capability, "Not determined by the probe."));
  }

  public boolean supports(Capability capability) {
    return status(capability).usable();
  }

  /**
   * Read strategy for the bulk engines. Derived rather than stored, so it can never disagree with
   * the capability it is derived from.
   */
  public BulkReadStrategy bulkReadStrategy() {
    return supports(Capability.TOKEN_RANGE_SCAN)
        ? BulkReadStrategy.TOKEN_RANGE_SCAN
        : BulkReadStrategy.PLAIN_PAGING;
  }

  /** The narrow value handed to feature code through {@link SessionRegistry#capabilities}. */
  public ClusterCapabilities capabilities() {
    Set<Capability> usable = new LinkedHashSet<>();
    for (Capability capability : Capability.values()) {
      if (supports(capability)) {
        usable.add(capability);
      }
    }
    return new ClusterCapabilities(flavor, versionString(), usable);
  }

  /** DSE reports both a Cassandra release and its own version; prefer the one users recognise. */
  public String versionString() {
    if (dseVersion != null && !dseVersion.isBlank()) {
      return dseVersion;
    }
    return releaseVersion == null || releaseVersion.isBlank() ? "unknown" : releaseVersion;
  }

  public Optional<String> dseVersionOpt() {
    return Optional.ofNullable(dseVersion == null || dseVersion.isBlank() ? null : dseVersion);
  }

  public static ClusterProbeResult unknown() {
    return new ClusterProbeResult(
        ClusterFlavor.UNKNOWN, null, null, null, null, null, 0, List.of(), Map.of(), Instant.EPOCH);
  }
}
