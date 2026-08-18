package io.cassyx.vector.api;

import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import java.util.Set;

/**
 * Builds {@code SELECT ... ORDER BY <col> ANN OF [...] LIMIT k} statements, with optional SAI
 * predicates (hybrid queries) and {@code similarity_*} score projections (plan section 6).
 *
 * <p>Two renderings, deliberately:
 *
 * <ul>
 *   <li>{@link #build} / {@link #preview} inline the vector as a CQL literal, because the preview
 *       pane must be copy-pasteable into cqlsh verbatim.
 *   <li>{@link #statement} binds the vector and every predicate value as parameters, because that
 *       is what actually gets executed and a 1536-float literal has no business in the query cache.
 * </ul>
 */
public interface AnnQueryBuilder {

  /** The generated statement, with the vector inlined. */
  String build(AnnQuery query);

  /** The statement plus warnings, abbreviated form and the index it will use. */
  AnnQueryPreview preview(AnnQuery query);

  /**
   * As {@link #preview(AnnQuery)}, additionally warning about predicates on columns with no SAI
   * index.
   *
   * @param saiIndexedColumns columns known to carry an SAI index; {@code null} means "unknown", in
   *     which case no predicate warnings are produced
   */
  AnnQueryPreview preview(AnnQuery query, Set<String> saiIndexedColumns);

  /** An executable statement with the vector and predicate values bound, not inlined. */
  SimpleStatement statement(AnnQuery query);
}
