package io.cassyx.core.api.schema;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * The four always-populated tabs of the table info panel: FIELDS, INDEXES, COMMENT and DEFINITION
 * (plan section 4). STATISTICS is a separate, expensive call.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TableInfo(
    SchemaIdentity identity,
    List<ColumnInfo> fields,
    List<IndexInfo> indexes,
    String comment,
    String definition,
    List<SchemaIdentity> views,
    boolean statisticsAvailable) {

  public TableInfo {
    fields = fields == null ? List.of() : List.copyOf(fields);
    indexes = indexes == null ? List.of() : List.copyOf(indexes);
    views = views == null ? List.of() : List.copyOf(views);
  }
}
