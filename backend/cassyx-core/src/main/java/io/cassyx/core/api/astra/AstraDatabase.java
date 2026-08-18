package io.cassyx.core.api.astra;

import java.util.List;

/** One entry of {@code GET /v2/databases}; feeds the UI database picker so no UUID typing. */
public record AstraDatabase(String id, String name, String status, List<String> regions) {

  public AstraDatabase {
    regions = regions == null ? List.of() : List.copyOf(regions);
  }
}
