package io.cassyx.api.schema;

import io.cassyx.core.api.Capability;
import io.cassyx.core.api.schema.ColumnAlteration;
import io.cassyx.core.api.schema.ColumnDefinition;
import io.cassyx.core.api.schema.ColumnInfo;
import io.cassyx.core.api.schema.DdlExecutionResult;
import io.cassyx.core.api.schema.SchemaReader;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Columns: add / rename / retype / drop, including collections, frozen types, tuples, counters and
 * {@code vector<float, N>} (plan section 4).
 */
@RestController
@RequestMapping("/api/connections/{connectionId}/keyspaces/{keyspace}/tables/{table}/columns")
public class ColumnController {

  private final SchemaReader reader;
  private final SchemaSessions sessions;
  private final DdlService ddl;

  public ColumnController(SchemaReader reader, SchemaSessions sessions, DdlService ddl) {
    this.reader = reader;
    this.sessions = sessions;
    this.ddl = ddl;
  }

  @GetMapping
  public List<ColumnInfo> list(
      @PathVariable String connectionId, @PathVariable String keyspace, @PathVariable String table) {
    return reader.columns(sessions.session(connectionId), keyspace, table);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public DdlExecutionResult add(
      @PathVariable String connectionId,
      @PathVariable String keyspace,
      @PathVariable String table,
      @RequestBody ColumnDefinition definition) {
    if (definition.type() != null
        && definition.type().toLowerCase(Locale.ROOT).replace(" ", "").startsWith("vector<")) {
      sessions.require(
          connectionId,
          Capability.VECTOR_ANN,
          "vector<float, N> columns require Cassandra 5.x, DSE 6.8+ or Astra.");
    }
    reader.table(sessions.session(connectionId), keyspace, table);
    return ddl.apply(connectionId, ddl.generator().addColumn(keyspace, table, definition));
  }

  @PatchMapping("/{column}")
  public DdlExecutionResult alter(
      @PathVariable String connectionId,
      @PathVariable String keyspace,
      @PathVariable String table,
      @PathVariable String column,
      @RequestBody ColumnAlteration alteration) {
    reader.table(sessions.session(connectionId), keyspace, table);
    return ddl.apply(
        connectionId, ddl.generator().alterColumn(keyspace, table, column, alteration));
  }

  @DeleteMapping("/{column}")
  public DdlExecutionResult drop(
      @PathVariable String connectionId,
      @PathVariable String keyspace,
      @PathVariable String table,
      @PathVariable String column) {
    reader.table(sessions.session(connectionId), keyspace, table);
    return ddl.apply(connectionId, ddl.generator().dropColumn(keyspace, table, column));
  }
}
