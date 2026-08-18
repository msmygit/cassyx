package io.cassyx.core.api.schema;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** Per-token-range row estimate. Tokens are strings - Murmur3 exceeds the JS safe-integer range. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TokenRangeRowCount(String start, String end, long rows, List<String> replicas) {

  public TokenRangeRowCount {
    replicas = replicas == null ? List.of() : List.copyOf(replicas);
  }
}
