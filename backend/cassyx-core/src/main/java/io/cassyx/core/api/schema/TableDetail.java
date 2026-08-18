package io.cassyx.core.api.schema;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** A table: the contract's {@code Table} schema. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TableDetail(
    SchemaIdentity identity,
    String name,
    String keyspace,
    List<ColumnInfo> columns,
    PrimaryKeyDefinition primaryKey,
    TableOptions options,
    List<IndexInfo> indexes,
    List<String> viewNames,
    boolean virtual,
    boolean system,
    boolean hasCounters,
    boolean hasVectorColumns) {

  public TableDetail {
    columns = columns == null ? List.of() : List.copyOf(columns);
    indexes = indexes == null ? List.of() : List.copyOf(indexes);
    viewNames = viewNames == null ? List.of() : List.copyOf(viewNames);
  }
}
