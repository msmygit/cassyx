package io.cassyx.vector.api;

import java.util.List;

/**
 * The generated, editable ANN statement. Generated CQL is always shown in the "Preview CQL" pane
 * before anything is executed (plan section 4/6) - nothing here runs on its own.
 *
 * @param cql the full statement, with the vector inlined for copy-paste fidelity
 * @param abbreviatedCql the same statement with vector literals elided, for narrow panes
 * @param warnings non-blocking advisories, e.g. a predicate on a column with no SAI index
 */
public record AnnQueryPreview(
    String cql,
    String abbreviatedCql,
    int dimensions,
    List<String> similarityColumns,
    List<String> warnings,
    SaiIndexDescriptor indexUsed) {

  public AnnQueryPreview {
    similarityColumns = similarityColumns == null ? List.of() : List.copyOf(similarityColumns);
    warnings = warnings == null ? List.of() : List.copyOf(warnings);
  }
}
