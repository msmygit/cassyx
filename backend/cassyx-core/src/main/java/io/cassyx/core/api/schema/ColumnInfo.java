package io.cassyx.core.api.schema;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One column of a table or view. Carries its own {@link SchemaIdentity} so the FIELDS tab, the tree
 * and drag payloads never resolve a keyspace from context.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ColumnInfo(
    SchemaIdentity identity,
    String name,
    String type,
    ColumnKind kind,
    int position,
    ClusteringOrder clusteringOrder,
    boolean frozen,
    boolean collection,
    boolean counter,
    boolean vector,
    Integer vectorDimensions,
    String comment,
    boolean indexed) {}
