package io.cassyx.api.schema;

import io.cassyx.core.api.schema.SchemaObjectKind;
import io.cassyx.core.api.schema.SchemaReader;
import io.cassyx.core.api.schema.SchemaSearchResult;
import io.cassyx.core.api.schema.SchemaTreeSnapshot;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The object browser: {@code GET .../schema/tree} and {@code GET .../schema/search}.
 *
 * <p>Both read the driver's live metadata cache; neither polls {@code system_schema} (plan
 * section 4). Every node in the response carries its own fully-qualified identity.
 */
@RestController
@RequestMapping("/api/connections/{connectionId}/schema")
public class SchemaTreeController {

  private final SchemaReader reader;
  private final SchemaSessions sessions;

  public SchemaTreeController(SchemaReader reader, SchemaSessions sessions) {
    this.reader = reader;
    this.sessions = sessions;
  }

  @GetMapping("/tree")
  public SchemaTreeSnapshot tree(
      @PathVariable String connectionId,
      @RequestParam(defaultValue = "false") boolean includeSystem) {
    return reader.tree(sessions.session(connectionId), connectionId, includeSystem);
  }

  @GetMapping("/search")
  public SchemaSearchResult search(
      @PathVariable String connectionId,
      @RequestParam String q,
      @RequestParam(required = false) List<String> kinds,
      @RequestParam(defaultValue = "false") boolean includeSystem,
      @RequestParam(defaultValue = "100") int limit) {
    return reader.search(
        sessions.session(connectionId), q, parseKinds(kinds), includeSystem, Math.min(limit, 500));
  }

  /** Unknown kind names are rejected rather than silently widening the search. */
  private static Set<SchemaObjectKind> parseKinds(List<String> kinds) {
    if (kinds == null || kinds.isEmpty()) {
      return Set.of();
    }
    Set<SchemaObjectKind> parsed = new LinkedHashSet<>();
    for (String kind : kinds) {
      if (kind == null || kind.isBlank()) {
        continue;
      }
      parsed.add(SchemaObjectKind.valueOf(kind.trim().toUpperCase(Locale.ROOT)));
    }
    return parsed;
  }
}
