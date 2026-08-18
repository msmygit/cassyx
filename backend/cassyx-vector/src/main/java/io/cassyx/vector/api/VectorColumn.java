package io.cassyx.vector.api;

/** A {@code vector<float, N>} column. */
public record VectorColumn(String keyspace, String table, String column, int dimensions) {

  public VectorColumn {
    if (dimensions <= 0) {
      throw new IllegalArgumentException("Vector dimensions must be positive");
    }
  }

  public String cqlType() {
    return "vector<float, " + dimensions + ">";
  }
}
