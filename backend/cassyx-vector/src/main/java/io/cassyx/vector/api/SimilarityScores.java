package io.cassyx.vector.api;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Inspector-panel arithmetic: magnitude of each vector plus their similarity under each requested
 * function (plan section 6). Computed server-side so the browser never handles large float arrays
 * and so the numbers match the CQL {@code similarity_*} functions exactly.
 */
public record SimilarityScores(
    int dimensions,
    Map<SimilarityFunction, Double> scores,
    double leftMagnitude,
    double rightMagnitude) {

  public SimilarityScores {
    scores = scores == null ? Map.of() : Map.copyOf(scores);
  }

  /** The scores keyed by their CQL name, which is what the API contract returns. */
  public Map<String, Double> scoresByCqlName() {
    Map<String, Double> byName = new LinkedHashMap<>();
    for (SimilarityFunction function : SimilarityFunction.values()) {
      Double score = scores.get(function);
      if (score != null) {
        byName.put(function.cqlValue(), score);
      }
    }
    return byName;
  }
}
