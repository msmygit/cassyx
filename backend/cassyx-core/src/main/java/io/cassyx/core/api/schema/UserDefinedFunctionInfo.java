package io.cassyx.core.api.schema;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** A UDF. CQL functions are overloadable, so the signature is part of the identity. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UserDefinedFunctionInfo(
    SchemaIdentity identity,
    String name,
    String signature,
    List<FunctionArgument> arguments,
    String returnType,
    String language,
    String body,
    UdfNullHandling nullHandling,
    boolean deterministic) {

  public UserDefinedFunctionInfo {
    arguments = arguments == null ? List.of() : List.copyOf(arguments);
  }
}
