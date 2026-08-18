package io.cassyx.core.api.schema;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Input to CREATE/ALTER KEYSPACE. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KeyspaceDefinition(
    String name, ReplicationSettings replication, Boolean durableWrites, Boolean ifNotExists) {}
