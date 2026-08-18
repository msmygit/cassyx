package io.cassyx.core.api.schema;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** Input to CREATE TABLE. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TableDefinition(
    String name,
    List<ColumnDefinition> columns,
    PrimaryKeyDefinition primaryKey,
    TableOptions options,
    Boolean ifNotExists) {

  public TableDefinition {
    columns = columns == null ? List.of() : List.copyOf(columns);
  }
}
