package io.cassyx.core.api.schema;

import java.util.Locale;
import java.util.Set;

/** The "Show system keyspaces" filter of plan section 4. */
public interface SystemKeyspaces {

  /** Vendor-internal keyspaces that are hidden unless the toggle is on. */
  Set<String> NAMES =
      Set.of(
          "system",
          "system_auth",
          "system_distributed",
          "system_schema",
          "system_traces",
          "system_views",
          "system_virtual_schema",
          "data_endpoint_auth",
          "datastax_sla",
          "dse_system",
          "dse_system_local",
          "dse_security",
          "dse_leases",
          "dse_perf",
          "dse_insights",
          "dse_insights_local",
          "solr_admin",
          "oxsettings",
          "osssettings");

  /** Matches the explicit list plus the {@code system_} / {@code dse_} prefixes. */
  static boolean matches(String name) {
    if (name == null) {
      return false;
    }
    String lower = name.toLowerCase(Locale.ROOT);
    return NAMES.contains(lower) || lower.startsWith("system_") || lower.startsWith("dse_");
  }
}
