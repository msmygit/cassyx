package io.cassyx.core.api.query;

/**
 * Result-set column descriptor. Mirrors the contract's {@code ColumnMetadata}: it is what the grid
 * uses to choose a renderer/editor, and {@link #primaryKeyColumn()} is what decides whether a result
 * set is editable at all (plan section 7).
 */
public record ColumnInfo(
    String name,
    String type,
    String keyspace,
    String table,
    boolean primaryKeyColumn,
    String kind,
    boolean collection,
    boolean vector,
    Integer vectorDimensions,
    boolean udt,
    boolean similarity) {

  public static ColumnInfo simple(String name, String type) {
    return new ColumnInfo(name, type, null, null, false, null, false, false, null, false, false);
  }
}
