package io.cassyx.core.api.schema;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/** CQL can add and rename UDT fields; it cannot drop them. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UserDefinedTypeAlteration(
    List<UserDefinedTypeField> addFields, Map<String, String> renameFields) {

  public UserDefinedTypeAlteration {
    addFields = addFields == null ? List.of() : List.copyOf(addFields);
    renameFields = renameFields == null ? Map.of() : Map.copyOf(renameFields);
  }
}
