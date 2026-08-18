package io.cassyx.core.api.query;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A {@code BEGIN [UNLOGGED|COUNTER] BATCH ... APPLY BATCH} (plan section 5.1).
 *
 * @param previewOnly assemble and analyse without executing, so the user sees the partition-spanning
 *     warning before anything hits the cluster
 */
public record BatchSpec(
    Kind kind,
    List<Statement> statements,
    String keyspace,
    String consistency,
    String serialConsistency,
    Long timestampMicros,
    boolean previewOnly) {

  public BatchSpec {
    kind = kind == null ? Kind.LOGGED : kind;
    statements = statements == null ? List.of() : List.copyOf(statements);
  }

  public enum Kind {
    LOGGED,
    UNLOGGED,
    COUNTER
  }

  /** One statement of the batch, with its wire-encoded bind values. */
  public record Statement(String cql, List<Object> positionalValues, Map<String, Object> namedValues) {

    public Statement {
      Objects.requireNonNull(cql, "cql");
      positionalValues = positionalValues == null ? List.of() : List.copyOf(positionalValues);
      namedValues = namedValues == null ? Map.of() : Map.copyOf(namedValues);
    }

    public boolean hasBindValues() {
      return !positionalValues.isEmpty() || !namedValues.isEmpty();
    }
  }
}
