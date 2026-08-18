package io.cassyx.vector.api;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A requested SAI index (plan section 6).
 *
 * <p>Vector indexes use {@code similarityFunction} + {@code sourceModel}; scalar text indexes use
 * the analyzer / normalization / case-sensitivity options instead. Anything unrecognised goes
 * through {@code options} unchanged, so a new Cassandra option needs no code change here.
 *
 * <p><b>{@code sourceModel} is not portable.</b> Astra and DSE accept it; <b>Apache Cassandra 5.0
 * rejects it</b> with {@code Properties specified [source_model] are not understood by
 * StorageAttachedIndex}. Verified against a real 5.0 cluster in {@code SaiIndexLifecycleIT}. The UI
 * must therefore offer the field only where the cluster supports it - leaving it {@code null} is
 * always safe, and Cassandra defaults the index tuning itself.
 *
 * @param target column name, or a collection selector such as {@code values(tags)}
 */
public record SaiIndexDefinition(
    String name,
    String target,
    SimilarityFunction similarityFunction,
    String sourceModel,
    Boolean caseSensitive,
    Boolean normalize,
    Boolean asciiOnly,
    String analyzer,
    Map<String, String> options,
    boolean ifNotExists) {

  public SaiIndexDefinition {
    if (name == null || name.isBlank()) {
      throw new VectorException("Index name is required");
    }
    if (target == null || target.isBlank()) {
      throw new VectorException("Index target is required");
    }
    options = options == null ? Map.of() : Map.copyOf(options);
  }

  public static Builder builder(String name, String target) {
    return new Builder(name, target);
  }

  /** Whether this definition carries any vector-specific option. */
  public boolean vectorIndex() {
    return similarityFunction != null || sourceModel != null;
  }

  /**
   * The full {@code WITH OPTIONS} map, in a stable order: typed options first, raw {@code options}
   * merged last so a caller can always override.
   */
  public Map<String, String> withOptions() {
    Map<String, String> merged = new LinkedHashMap<>();
    if (similarityFunction != null) {
      merged.put("similarity_function", similarityFunction.cqlValue());
    }
    if (sourceModel != null && !sourceModel.isBlank()) {
      merged.put("source_model", sourceModel.trim());
    }
    if (caseSensitive != null) {
      merged.put("case_sensitive", caseSensitive.toString());
    }
    if (normalize != null) {
      merged.put("normalize", normalize.toString());
    }
    if (asciiOnly != null) {
      merged.put("ascii", asciiOnly.toString());
    }
    if (analyzer != null && !analyzer.isBlank()) {
      merged.put("index_analyzer", analyzer.trim());
    }
    merged.putAll(options);
    return merged;
  }

  /** Mutable builder — ten optional components is past the point a constructor call is readable. */
  public static final class Builder {

    private final String name;
    private final String target;
    private SimilarityFunction similarityFunction;
    private String sourceModel;
    private Boolean caseSensitive;
    private Boolean normalize;
    private Boolean asciiOnly;
    private String analyzer;
    private Map<String, String> options = Map.of();
    private boolean ifNotExists = true;

    private Builder(String name, String target) {
      this.name = name;
      this.target = target;
    }

    public Builder similarityFunction(SimilarityFunction value) {
      this.similarityFunction = value;
      return this;
    }

    public Builder sourceModel(String value) {
      this.sourceModel = value;
      return this;
    }

    public Builder caseSensitive(Boolean value) {
      this.caseSensitive = value;
      return this;
    }

    public Builder normalize(Boolean value) {
      this.normalize = value;
      return this;
    }

    public Builder asciiOnly(Boolean value) {
      this.asciiOnly = value;
      return this;
    }

    public Builder analyzer(String value) {
      this.analyzer = value;
      return this;
    }

    public Builder options(Map<String, String> value) {
      this.options = value == null ? Map.of() : Map.copyOf(value);
      return this;
    }

    public Builder ifNotExists(boolean value) {
      this.ifNotExists = value;
      return this;
    }

    public SaiIndexDefinition build() {
      return new SaiIndexDefinition(
          name,
          target,
          similarityFunction,
          sourceModel,
          caseSensitive,
          normalize,
          asciiOnly,
          analyzer,
          options,
          ifNotExists);
    }
  }
}
