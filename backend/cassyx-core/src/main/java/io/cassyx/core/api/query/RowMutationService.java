package io.cassyx.core.api.query;

import com.datastax.oss.driver.api.core.CqlSession;
import java.util.List;

/**
 * Row-level CRUD and statement generation for the data grid (plan section 7).
 *
 * <p><b>The hard rule lives here:</b> every operation that identifies an existing row requires the
 * COMPLETE primary key. A result set that does not project it is refused for editing, and the
 * refusal names the missing columns rather than silently greying the grid out.
 */
public interface RowMutationService {

  /** Reads the primary-key shape and column types of a table from live driver metadata. */
  TableKeyInfo tableKey(CqlSession session, String keyspace, String table);

  /**
   * Can a result set projecting {@code projectedColumns} be edited in place?
   *
   * <p>Never throws for a read-only verdict - the verdict itself carries the explanation.
   */
  EditabilityVerdict editability(CqlSession session, String keyspace, String table, List<String> projectedColumns);

  RowMutationOutcome insert(CqlSession session, String keyspace, String table, RowInsertSpec spec);

  RowMutationOutcome update(CqlSession session, String keyspace, String table, RowUpdateSpec spec);

  RowMutationOutcome delete(CqlSession session, String keyspace, String table, RowDeleteSpec spec);

  /** Pure generation - nothing is executed. */
  GeneratedStatements generate(
      CqlSession session, String keyspace, String table, StatementGenerationSpec spec);
}
