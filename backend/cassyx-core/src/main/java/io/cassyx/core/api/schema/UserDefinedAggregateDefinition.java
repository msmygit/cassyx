package io.cassyx.core.api.schema;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** Input to CREATE AGGREGATE. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UserDefinedAggregateDefinition(
    String name,
    List<String> argumentTypes,
    String stateFunction,
    String stateType,
    String finalFunction,
    String initCondition,
    Boolean orReplace,
    Boolean ifNotExists) {

  public UserDefinedAggregateDefinition {
    argumentTypes = argumentTypes == null ? List.of() : List.copyOf(argumentTypes);
  }
}
