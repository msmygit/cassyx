package io.cassyx.core.api.schema;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/**
 * One index. Populating this is what makes the INDEXES tab work - the prior-art prototype left it
 * permanently empty (plan section 4).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IndexInfo(
    SchemaIdentity identity,
    String name,
    String target,
    IndexKind kind,
    String className,
    Map<String, String> options) {

  public IndexInfo {
    options = options == null ? Map.of() : Map.copyOf(options);
  }
}
