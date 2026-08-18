package io.cassyx.api.connections.dto;

import io.cassyx.core.api.astra.AstraDatabase;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * One entry of the Astra database picker, so nobody ever types a database UUID (plan section 3.1,
 * deviation 3).
 */
public record AstraDatabaseView(
    String id,
    String name,
    String status,
    String cloudProvider,
    List<String> regions,
    String defaultKeyspace,
    List<String> keyspaces) {

  private static final Set<String> KNOWN_STATUSES =
      Set.of(
          "ACTIVE",
          "PENDING",
          "INITIALIZING",
          "HIBERNATED",
          "PARKED",
          "MAINTENANCE",
          "TERMINATING",
          "TERMINATED",
          "ERROR");

  public AstraDatabaseView {
    regions = regions == null ? List.of() : List.copyOf(regions);
    keyspaces = keyspaces == null ? List.of() : List.copyOf(keyspaces);
    status = normalise(status);
  }

  /** Anything Astra invents later maps to UNKNOWN rather than breaking the enum in the client. */
  private static String normalise(String status) {
    if (status == null || status.isBlank()) {
      return "UNKNOWN";
    }
    String upper = status.trim().toUpperCase(Locale.ROOT);
    return KNOWN_STATUSES.contains(upper) ? upper : "UNKNOWN";
  }

  public static AstraDatabaseView from(AstraDatabase database) {
    return new AstraDatabaseView(
        database.id(), database.name(), database.status(), null, database.regions(), null, List.of());
  }
}
