package io.cassyx.core.api.schema;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** Input to CREATE MATERIALIZED VIEW. Empty {@code selectedColumns} means {@code SELECT *}. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MaterializedViewDefinition(
    String name,
    String baseTable,
    List<String> selectedColumns,
    PrimaryKeyDefinition primaryKey,
    String whereClause,
    TableOptions options,
    Boolean ifNotExists) {

  public MaterializedViewDefinition {
    selectedColumns = selectedColumns == null ? List.of() : List.copyOf(selectedColumns);
  }
}
