package io.cassyx.core.api.schema;

import com.fasterxml.jackson.annotation.JsonInclude;

/** One hit from the schema search box the prior art lacked entirely (plan section 4). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SchemaSearchMatch(
    SchemaIdentity identity,
    String label,
    SchemaObjectKind kind,
    SearchMatchKind matchedOn,
    String context) {}
