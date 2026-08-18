package io.cassyx.core.api.schema;

import com.fasterxml.jackson.annotation.JsonInclude;

/** One field of a UDT. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UserDefinedTypeField(String name, String type) {}
