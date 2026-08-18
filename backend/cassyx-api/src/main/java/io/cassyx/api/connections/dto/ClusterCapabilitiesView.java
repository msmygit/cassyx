package io.cassyx.api.connections.dto;

import io.cassyx.core.api.Capability;
import io.cassyx.core.api.ClusterProbeResult;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The connect-time capability probe result, keyed by capability name so the UI can look a feature
 * up directly instead of scanning an array (plan section 7.1).
 */
public record ClusterCapabilitiesView(
    String flavour,
    String clusterName,
    String releaseVersion,
    String dseVersion,
    String protocolVersion,
    String partitioner,
    int nodeCount,
    List<String> datacenters,
    Map<String, CapabilityView> capabilities,
    String bulkFallback,
    Instant probedAt) {

  public static ClusterCapabilitiesView from(ClusterProbeResult probe) {
    Map<String, CapabilityView> views = new LinkedHashMap<>();
    for (Capability capability : Capability.values()) {
      views.put(capability.wireName(), CapabilityView.from(probe.status(capability)));
    }
    return new ClusterCapabilitiesView(
        probe.flavor().name(),
        probe.clusterName(),
        probe.releaseVersion(),
        probe.dseVersion(),
        probe.protocolVersion(),
        probe.partitioner(),
        probe.nodeCount(),
        probe.datacenters(),
        views,
        probe.bulkReadStrategy().name(),
        probe.probedAt());
  }
}
