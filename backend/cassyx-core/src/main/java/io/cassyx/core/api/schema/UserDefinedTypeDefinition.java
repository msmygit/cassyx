package io.cassyx.core.api.schema;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** Input to CREATE TYPE. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UserDefinedTypeDefinition(
    String name, List<UserDefinedTypeField> fields, Boolean ifNotExists) {

  public UserDefinedTypeDefinition {
    fields = fields == null ? List.of() : List.copyOf(fields);
  }
}
