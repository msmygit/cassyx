package io.cassyx.core.api.schema;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** A UDA. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UserDefinedAggregateInfo(
    SchemaIdentity identity,
    String name,
    String signature,
    List<String> argumentTypes,
    String stateFunction,
    String stateType,
    String finalFunction,
    String initCondition,
    String returnType) {

  public UserDefinedAggregateInfo {
    argumentTypes = argumentTypes == null ? List.of() : List.copyOf(argumentTypes);
  }
}
