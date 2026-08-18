package io.cassyx.core.api.schema;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/** {@code SimpleStrategy} RF, or {@code NetworkTopologyStrategy} per-DC RFs (plan section 4). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReplicationSettings(
    ReplicationStrategy strategy, Integer replicationFactor, Map<String, Integer> datacenters) {

  public ReplicationSettings {
    datacenters = datacenters == null ? Map.of() : Map.copyOf(datacenters);
  }

  public static ReplicationSettings simple(int replicationFactor) {
    return new ReplicationSettings(ReplicationStrategy.SimpleStrategy, replicationFactor, Map.of());
  }

  public static ReplicationSettings networkTopology(Map<String, Integer> datacenters) {
    return new ReplicationSettings(
        ReplicationStrategy.NetworkTopologyStrategy, null, datacenters);
  }
}
