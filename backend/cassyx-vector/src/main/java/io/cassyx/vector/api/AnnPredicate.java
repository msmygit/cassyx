package io.cassyx.vector.api;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * One SAI predicate of a hybrid query — SAI filtering plus ANN in a single statement
 * (plan section 6).
 *
 * <p>The operator is checked against a closed set rather than pasted through, because it is the
 * only part of a predicate that cannot be escaped: an operator is CQL syntax, not a value.
 */
public record AnnPredicate(String column, String operator, Object value) {

  /** Exactly the operators the API contract enumerates. */
  public static final Set<String> OPERATORS =
      Set.of("=", "<", "<=", ">", ">=", "IN", "CONTAINS", "CONTAINS KEY", ":");

  public AnnPredicate {
    if (column == null || column.isBlank()) {
      throw new VectorException("Predicate column is required");
    }
    operator = operator == null ? "" : operator.trim().toUpperCase(Locale.ROOT);
    if (!OPERATORS.contains(operator)) {
      throw new VectorException(
          "Unsupported predicate operator '" + operator + "'; expected one of " + sortedOperators());
    }
    if ("IN".equals(operator) && !(value instanceof List<?>)) {
      throw new VectorException("The IN operator needs a list of values");
    }
  }

  public static AnnPredicate equalTo(String column, Object value) {
    return new AnnPredicate(column, "=", value);
  }

  private static List<String> sortedOperators() {
    return OPERATORS.stream().sorted().toList();
  }
}
