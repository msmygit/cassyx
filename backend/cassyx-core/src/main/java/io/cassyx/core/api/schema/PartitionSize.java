package io.cassyx.core.api.schema;

import com.fasterxml.jackson.annotation.JsonInclude;

/** One of the top-N largest partitions - the skew signal that drives oversplitting. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PartitionSize(String partitionKey, long rows, Long sizeBytes) {}
