package io.cassyx.api.schema;

import io.cassyx.core.api.schema.DdlAction;
import io.cassyx.core.api.schema.DdlObjectType;
import java.util.Map;

/**
 * Structured DDL request for the "Preview CQL" pane.
 *
 * <p>{@code definition} is deliberately untyped: a single discriminated union across ten object
 * types generates worse clients than a documented mapping (contract note on
 * {@code DdlGenerateRequest}). {@link DdlService} converts it to the matching typed record.
 */
public record DdlGenerateRequest(
    DdlObjectType objectType,
    DdlAction action,
    String keyspace,
    String table,
    Map<String, Object> definition) {}
