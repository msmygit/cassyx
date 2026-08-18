package io.cassyx.core.api.schema;

import com.fasterxml.jackson.annotation.JsonInclude;

/** One argument of a UDF. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FunctionArgument(String name, String type) {}
