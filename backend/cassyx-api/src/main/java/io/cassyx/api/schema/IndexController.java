package io.cassyx.api.schema;

import io.cassyx.core.api.Capability;
import io.cassyx.core.api.schema.DdlExecutionResult;
import io.cassyx.core.api.schema.IndexDefinition;
import io.cassyx.core.api.schema.IndexInfo;
import io.cassyx.core.api.schema.IndexKind;
import io.cassyx.core.api.schema.SchemaReader;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Indexes: SAI, legacy 2i and DSE Search (plan sections 4 and 6).
 *
 * <p>The list endpoint is what finally populates the INDEXES tab, and each entry carries the
 * identity of the table it belongs to so a drop never has to be inferred from the tree.
 */
@RestController
@RequestMapping("/api/connections/{connectionId}/keyspaces/{keyspace}/tables/{table}/indexes")
public class IndexController {

  private final SchemaReader reader;
  private final SchemaSessions sessions;
  private final DdlService ddl;

  public IndexController(SchemaReader reader, SchemaSessions sessions, DdlService ddl) {
    this.reader = reader;
    this.sessions = sessions;
    this.ddl = ddl;
  }

  @GetMapping
  public List<IndexInfo> list(
      @PathVariable String connectionId, @PathVariable String keyspace, @PathVariable String table) {
    return reader.indexes(sessions.session(connectionId), keyspace, table);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public DdlExecutionResult create(
      @PathVariable String connectionId,
      @PathVariable String keyspace,
      @PathVariable String table,
      @RequestBody IndexDefinition definition) {
    if (definition.kind() == IndexKind.SAI) {
      sessions.require(
          connectionId,
          Capability.SAI,
          "SAI indexes require Cassandra 5.x, DSE 6.8+ or Astra.");
    } else if (definition.kind() == IndexKind.DSE_SEARCH) {
      sessions.require(
          connectionId, Capability.DSE_SEARCH, "DSE Search indexes require DataStax Enterprise.");
    }
    reader.table(sessions.session(connectionId), keyspace, table);
    return ddl.apply(connectionId, ddl.generator().createIndex(keyspace, table, definition));
  }

  @DeleteMapping("/{index}")
  public DdlExecutionResult drop(
      @PathVariable String connectionId,
      @PathVariable String keyspace,
      @PathVariable String table,
      @PathVariable String index,
      @RequestParam(defaultValue = "true") boolean ifExists) {
    return ddl.apply(connectionId, ddl.generator().dropIndex(keyspace, table, index, ifExists));
  }
}
