package io.cassyx.vector.api;

import java.util.Map;

/**
 * An SAI index as it exists in the cluster, read from driver schema metadata.
 *
 * @param target column name, or a collection selector such as {@code values(tags)}
 * @param vectorIndex {@code true} when the target column is a {@code vector<float, N>}
 * @param options the full options map as stored, including analyzer and normalization keys
 */
public record SaiIndexDescriptor(
    String keyspace,
    String table,
    String name,
    String target,
    boolean vectorIndex,
    SimilarityFunction similarityFunction,
    String sourceModel,
    Map<String, String> options,
    String className) {

  /** The class every SAI index is backed by. */
  public static final String STORAGE_ATTACHED_INDEX = "StorageAttachedIndex";

  public SaiIndexDescriptor {
    options = options == null ? Map.of() : Map.copyOf(options);
  }

  public String qualifiedName() {
    return keyspace + "." + name;
  }
}
