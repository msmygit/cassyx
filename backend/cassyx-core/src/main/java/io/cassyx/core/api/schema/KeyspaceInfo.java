package io.cassyx.core.api.schema;

import com.fasterxml.jackson.annotation.JsonInclude;

/** A keyspace with its child-object counts, for the tree and the keyspace editor. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KeyspaceInfo(
    SchemaIdentity identity,
    String name,
    ReplicationSettings replication,
    boolean durableWrites,
    boolean system,
    int tableCount,
    int viewCount,
    int typeCount,
    int functionCount,
    int aggregateCount) {}
