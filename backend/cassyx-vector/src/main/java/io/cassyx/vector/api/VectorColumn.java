package io.cassyx.vector.api;

/**
 * A {@code vector<float, N>} column, plus the SAI index backing it if there is one.
 *
 * <p>{@code index == null} means ANN is unavailable on this column: the grid still renders the
 * sparkline and dimension badge, but the ANN builder must not offer it (plan section 6).
 */
public record VectorColumn(
    String keyspace,
    String table,
    String column,
    int dimensions,
    String elementType,
    SaiIndexDescriptor index) {

  /** Cassandra 5.x only supports {@code float} elements. */
  public static final String FLOAT = "float";

  public VectorColumn {
    if (dimensions <= 0) {
      throw new VectorException("Vector dimensions must be positive, got " + dimensions);
    }
    elementType = elementType == null || elementType.isBlank() ? FLOAT : elementType;
  }

  /** An unindexed {@code vector<float, N>} column. */
  public VectorColumn(String keyspace, String table, String column, int dimensions) {
    this(keyspace, table, column, dimensions, FLOAT, null);
  }

  public String cqlType() {
    return "vector<" + elementType + ", " + dimensions + ">";
  }

  public String qualifiedName() {
    return keyspace + "." + table + "." + column;
  }

  /** Whether {@code ORDER BY ... ANN OF} can be used against this column. */
  public boolean annCapable() {
    return index != null;
  }

  /** The index's similarity function, or {@code null} when the column is unindexed. */
  public SimilarityFunction similarityFunction() {
    return index == null ? null : index.similarityFunction();
  }

  /** Copy of this column with its SAI index attached. */
  public VectorColumn withIndex(SaiIndexDescriptor saiIndex) {
    return new VectorColumn(keyspace, table, column, dimensions, elementType, saiIndex);
  }
}
