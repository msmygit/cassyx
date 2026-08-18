package io.cassyx.core.api.schema;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** Input to CREATE FUNCTION. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UserDefinedFunctionDefinition(
    String name,
    List<FunctionArgument> arguments,
    String returnType,
    String language,
    String body,
    UdfNullHandling nullHandling,
    Boolean orReplace,
    Boolean ifNotExists) {

  public UserDefinedFunctionDefinition {
    arguments = arguments == null ? List.of() : List.copyOf(arguments);
  }
}
