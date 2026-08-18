package io.cassyx.core.api.schema;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/** A point-in-time read of the driver's live metadata cache. Never a {@code system_schema} poll. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SchemaTreeSnapshot(
    String connectionId,
    Instant generatedAt,
    boolean includeSystem,
    String schemaVersion,
    List<SchemaNode> keyspaces) {

  public SchemaTreeSnapshot {
    keyspaces = keyspaces == null ? List.of() : List.copyOf(keyspaces);
  }
}
