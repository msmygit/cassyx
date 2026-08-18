package io.cassyx.api.schema;

import io.cassyx.core.api.Capability;
import io.cassyx.core.api.schema.DdlExecutionResult;
import io.cassyx.core.api.schema.MaterializedViewDefinition;
import io.cassyx.core.api.schema.MaterializedViewInfo;
import io.cassyx.core.api.schema.SchemaReader;
import io.cassyx.core.api.schema.TableOptions;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Materialized views (plan section 4), gated on the {@code materializedViews} capability - they do
 * not exist on Astra DB or Amazon Keyspaces (plan section 7.1).
 */
@RestController
@RequestMapping("/api/connections/{connectionId}/keyspaces/{keyspace}/views")
public class MaterializedViewController {

  private static final String UNSUPPORTED =
      "Materialized views are unavailable on this cluster (Astra DB and Amazon Keyspaces do not "
          + "support them).";

  private final SchemaReader reader;
  private final SchemaSessions sessions;
  private final DdlService ddl;

  public MaterializedViewController(SchemaReader reader, SchemaSessions sessions, DdlService ddl) {
    this.reader = reader;
    this.sessions = sessions;
    this.ddl = ddl;
  }

  @GetMapping
  public List<MaterializedViewInfo> list(
      @PathVariable String connectionId, @PathVariable String keyspace) {
    return reader.views(sessions.session(connectionId), keyspace);
  }

  @GetMapping("/{view}")
  public MaterializedViewInfo get(
      @PathVariable String connectionId, @PathVariable String keyspace, @PathVariable String view) {
    return reader.view(sessions.session(connectionId), keyspace, view);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public DdlExecutionResult create(
      @PathVariable String connectionId,
      @PathVariable String keyspace,
      @RequestBody MaterializedViewDefinition definition) {
    sessions.require(connectionId, Capability.MATERIALIZED_VIEWS, UNSUPPORTED);
    return ddl.apply(connectionId, ddl.generator().createMaterializedView(keyspace, definition));
  }

  @PutMapping("/{view}")
  public DdlExecutionResult alter(
      @PathVariable String connectionId,
      @PathVariable String keyspace,
      @PathVariable String view,
      @RequestBody TableOptions options) {
    reader.view(sessions.session(connectionId), keyspace, view);
    return ddl.apply(connectionId, ddl.generator().alterMaterializedView(keyspace, view, options));
  }

  @DeleteMapping("/{view}")
  public DdlExecutionResult drop(
      @PathVariable String connectionId,
      @PathVariable String keyspace,
      @PathVariable String view,
      @RequestParam(defaultValue = "true") boolean ifExists) {
    return ddl.apply(connectionId, ddl.generator().dropMaterializedView(keyspace, view, ifExists));
  }
}
