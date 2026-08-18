package io.cassyx.core.api.schema;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** A materialized view. {@code baseTable} is a full identity, never inferred from tree position. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MaterializedViewInfo(
    SchemaIdentity identity,
    String name,
    SchemaIdentity baseTable,
    List<ColumnInfo> columns,
    PrimaryKeyDefinition primaryKey,
    String whereClause,
    boolean includesAllColumns,
    TableOptions options) {

  public MaterializedViewInfo {
    columns = columns == null ? List.of() : List.copyOf(columns);
  }
}
