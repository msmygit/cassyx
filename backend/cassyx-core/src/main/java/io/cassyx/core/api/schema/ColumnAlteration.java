package io.cassyx.core.api.schema;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Exactly one of {@code newName} (rename) or {@code newType} (retype). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ColumnAlteration(String newName, String newType) {}
