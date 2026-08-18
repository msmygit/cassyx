package io.cassyx.core.api.schema;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Input to CREATE TABLE / ALTER TABLE ADD.
 *
 * <p>{@code type} is CQL type text: scalars, {@code list<...>}, {@code set<...>},
 * {@code map<...,...>}, {@code tuple<...>}, {@code frozen<...>}, {@code counter}, a UDT name, or
 * {@code vector<float, N>}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ColumnDefinition(
    String name,
    String type,
    @JsonProperty("static") Boolean isStatic,
    String comment) {

  public static ColumnDefinition of(String name, String type) {
    return new ColumnDefinition(name, type, false, null);
  }

  public boolean staticColumn() {
    return Boolean.TRUE.equals(isStatic);
  }
}
