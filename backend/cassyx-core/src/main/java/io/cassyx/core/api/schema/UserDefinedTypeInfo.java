package io.cassyx.core.api.schema;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** A UDT plus the columns that reference it - why a drop may be refused. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UserDefinedTypeInfo(
    SchemaIdentity identity,
    String name,
    List<UserDefinedTypeField> fields,
    List<SchemaIdentity> usedBy) {

  public UserDefinedTypeInfo {
    fields = fields == null ? List.of() : List.copyOf(fields);
    usedBy = usedBy == null ? List.of() : List.copyOf(usedBy);
  }
}
