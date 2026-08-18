package io.cassyx.vector.api;

/**
 * Builds {@code SELECT ... ORDER BY <col> ANN OF [...] LIMIT k} statements, optionally with SAI
 * predicates and similarity projections (plan section 6).
 */
public interface AnnQueryBuilder {

  String build(AnnQuery query);
}
