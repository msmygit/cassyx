package io.cassyx.core.api.schema;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** Search results, with an explicit truncation flag rather than a silent cut-off. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SchemaSearchResult(String query, boolean truncated, List<SchemaSearchMatch> matches) {

  public SchemaSearchResult {
    matches = matches == null ? List.of() : List.copyOf(matches);
  }
}
