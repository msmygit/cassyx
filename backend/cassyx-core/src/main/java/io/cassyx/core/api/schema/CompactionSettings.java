package io.cassyx.core.api.schema;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/** Compaction class plus arbitrary subproperties, passed through untouched. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CompactionSettings(
    @JsonProperty("class") String strategyClass, Map<String, String> options) {

  public CompactionSettings {
    options = options == null ? Map.of() : Map.copyOf(options);
  }
}
