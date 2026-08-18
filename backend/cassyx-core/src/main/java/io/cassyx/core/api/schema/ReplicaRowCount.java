package io.cassyx.core.api.schema;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Per-replica row estimate from the count workflow (plan section 5.4). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReplicaRowCount(String endpoint, String datacenter, long rows) {}
