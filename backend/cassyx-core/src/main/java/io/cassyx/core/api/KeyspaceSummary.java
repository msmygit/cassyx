package io.cassyx.core.api;

import java.util.Map;

/** Immutable keyspace descriptor. */
public record KeyspaceSummary(
    String name, boolean durableWrites, Map<String, String> replication, boolean system) {

  public KeyspaceSummary {
    replication = replication == null ? Map.of() : Map.copyOf(replication);
  }
}
