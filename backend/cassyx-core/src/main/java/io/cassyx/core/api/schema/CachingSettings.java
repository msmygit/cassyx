package io.cassyx.core.api.schema;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Key and row caching. {@code rowsPerPartition} is {@code ALL}, {@code NONE} or a row count. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CachingSettings(String keys, String rowsPerPartition) {}
