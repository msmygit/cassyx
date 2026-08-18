package io.cassyx.vector.api;

import com.datastax.oss.driver.api.core.CqlSession;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Vector-aware schema access and arithmetic.
 *
 * <p><b>Driver version constraints.</b> Requires {@code java-driver-core} 4.19.0:
 *
 * <ul>
 *   <li><b>4.16+</b> introduced {@code CqlVector}, which implements {@code Iterable} plus
 *       {@code List}-like methods.
 *   <li><b>JAVA-3118</b> added vector support to the Schema Builder / QueryBuilder.
 *   <li><b>CASSJAVA-2</b>: {@code describe} emitted invalid CQL for vector columns on older 4.x.
 *       (The describe regression suite is workstream B's; the codec test below is ours.)
 *   <li><b>CASSANDRA-19333</b> was a silent data-corruption bug in {@code VectorCodec}. Guarded by
 *       a 1536-dimension element-by-element round-trip fidelity test - see
 *       {@code VectorCodecFidelityTest} and {@code VectorRoundTripIT}. That test is a corruption
 *       guard, not a formality: do not weaken it to a size or magnitude assertion.
 * </ul>
 */
public interface VectorService {

  /** Every {@code vector<float, N>} column on a table, with its SAI index attached if it has one. */
  List<VectorColumn> vectorColumns(CqlSession session, String keyspace, String table);

  /** One column by name, or {@code null} when it is not a vector column. */
  VectorColumn vectorColumn(CqlSession session, String keyspace, String table, String column);

  /** {@code ALTER TABLE ks.tbl ADD col vector<float, N>}, plus the index DDL when requested. */
  List<String> addColumnCql(String keyspace, String table, VectorColumnDefinition definition);

  /**
   * Reads one row's vector - the "find rows similar to this one" path.
   *
   * @param primaryKey the complete primary key of the reference row
   * @throws VectorException if the row does not exist or the key is incomplete
   */
  List<Float> readVector(
      CqlSession session,
      String keyspace,
      String table,
      String column,
      Map<String, Object> primaryKey);

  /** Magnitudes and similarity scores for the inspector panel. */
  SimilarityScores compare(
      List<Float> left, List<Float> right, Collection<SimilarityFunction> functions);

  /** Euclidean magnitude of a vector. */
  double magnitude(List<Float> vector);

  /** Capability gate for this session (plan section 7.1). */
  VectorCapabilities capabilities(CqlSession session);
}
