package io.cassyx.api.schema;

import io.cassyx.core.api.Capability;
import io.cassyx.core.api.schema.DdlExecutionResult;
import io.cassyx.core.api.schema.SchemaIdentity;
import io.cassyx.core.api.schema.SchemaNotFoundException;
import io.cassyx.core.api.schema.SchemaReader;
import io.cassyx.core.api.schema.TableDefinition;
import io.cassyx.core.api.schema.TableDetail;
import io.cassyx.core.api.schema.TableInfo;
import io.cassyx.core.api.schema.TableOptions;
import io.cassyx.core.api.schema.TableStatistics;
import io.cassyx.core.api.schema.TableStatisticsStore;
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
 * Tables plus the table info panel (plan section 4).
 *
 * <p>The panel's four tabs - FIELDS, INDEXES, COMMENT, DEFINITION - are all populated from
 * {@code /info}. INDEXES in particular: the prior-art prototype rendered that tab permanently
 * empty.
 */
@RestController
@RequestMapping("/api/connections/{connectionId}/keyspaces/{keyspace}/tables")
public class TableController {

  private final SchemaReader reader;
  private final SchemaSessions sessions;
  private final DdlService ddl;
  private final TableStatisticsStore statistics;

  public TableController(
      SchemaReader reader,
      SchemaSessions sessions,
      DdlService ddl,
      TableStatisticsStore statistics) {
    this.reader = reader;
    this.sessions = sessions;
    this.ddl = ddl;
    this.statistics = statistics;
  }

  @GetMapping
  public List<TableDetail> list(@PathVariable String connectionId, @PathVariable String keyspace) {
    return reader.tables(sessions.session(connectionId), keyspace);
  }

  @GetMapping("/{table}")
  public TableDetail get(
      @PathVariable String connectionId, @PathVariable String keyspace, @PathVariable String table) {
    return reader.table(sessions.session(connectionId), keyspace, table);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public DdlExecutionResult create(
      @PathVariable String connectionId,
      @PathVariable String keyspace,
      @RequestBody TableDefinition definition) {
    return ddl.apply(connectionId, ddl.generator().createTable(keyspace, definition));
  }

  @PutMapping("/{table}")
  public DdlExecutionResult alter(
      @PathVariable String connectionId,
      @PathVariable String keyspace,
      @PathVariable String table,
      @RequestBody TableOptions options) {
    reader.table(sessions.session(connectionId), keyspace, table);
    return ddl.apply(connectionId, ddl.generator().alterTable(keyspace, table, options));
  }

  @DeleteMapping("/{table}")
  public DdlExecutionResult drop(
      @PathVariable String connectionId,
      @PathVariable String keyspace,
      @PathVariable String table,
      @RequestParam(defaultValue = "true") boolean ifExists) {
    return ddl.apply(connectionId, ddl.generator().dropTable(keyspace, table, ifExists));
  }

  /** Gated on the {@code truncate} capability - Amazon Keyspaces does not support it. */
  @PostMapping("/{table}/truncate")
  public DdlExecutionResult truncate(
      @PathVariable String connectionId,
      @PathVariable String keyspace,
      @PathVariable String table) {
    sessions.require(
        connectionId, Capability.TRUNCATE, "TRUNCATE is not supported on this cluster.");
    reader.table(sessions.session(connectionId), keyspace, table);
    return ddl.apply(connectionId, ddl.generator().truncateTable(keyspace, table));
  }

  @GetMapping("/{table}/info")
  public TableInfo info(
      @PathVariable String connectionId, @PathVariable String keyspace, @PathVariable String table) {
    boolean statisticsAvailable = statistics.find(connectionId, keyspace, table).isPresent();
    return reader.tableInfo(sessions.session(connectionId), keyspace, table, statisticsAvailable);
  }

  @PutMapping("/{table}/comment")
  public DdlExecutionResult updateComment(
      @PathVariable String connectionId,
      @PathVariable String keyspace,
      @PathVariable String table,
      @RequestBody TableCommentUpdate update) {
    reader.table(sessions.session(connectionId), keyspace, table);
    return ddl.apply(
        connectionId,
        ddl.generator().alterTable(keyspace, table, TableOptions.comment(update.comment())));
  }

  /**
   * The cached snapshot from the COUNT job (plan section 5.4).
   *
   * <p>404 until such a job has run - the contract says so explicitly, and the UI turns that into
   * an offer to start one rather than an error.
   */
  @GetMapping("/{table}/statistics")
  public TableStatistics statistics(
      @PathVariable String connectionId, @PathVariable String keyspace, @PathVariable String table) {
    reader.table(sessions.session(connectionId), keyspace, table);
    return statistics
        .find(connectionId, keyspace, table)
        .orElseThrow(
            () ->
                new SchemaNotFoundException(
                    "No statistics have been computed for "
                        + keyspace
                        + "."
                        + table
                        + " yet. Start a COUNT job first.",
                    SchemaIdentity.table(keyspace, table)));
  }
}
