package io.cassyx.core.impl.query;

import com.datastax.oss.driver.api.core.ConsistencyLevel;
import com.datastax.oss.driver.api.core.CqlIdentifier;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.DefaultConsistencyLevel;
import com.datastax.oss.driver.api.core.cql.BatchStatement;
import com.datastax.oss.driver.api.core.cql.BatchableStatement;
import com.datastax.oss.driver.api.core.cql.BoundStatementBuilder;
import com.datastax.oss.driver.api.core.cql.ColumnDefinition;
import com.datastax.oss.driver.api.core.cql.ColumnDefinitions;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.datastax.oss.driver.api.core.cql.Statement;
import com.datastax.oss.driver.api.core.type.DataType;
import io.cassyx.core.api.CassyxCoreException;
import io.cassyx.core.api.query.CqlValueCodec;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds driver statements from the wire-level request shapes.
 *
 * <p>Bind values are decoded against the PREPARED statement's variable definitions rather than
 * guessed from the JSON type. Guessing is how {@code "1a2b3c4d-…"} ends up bound as {@code text} to
 * a {@code uuid} column and the query mysteriously returns nothing.
 */
final class StatementFactory {

  private final CqlValueCodec codec;

  StatementFactory(CqlValueCodec codec) {
    this.codec = codec;
  }

  /** A statement with all its bind values applied, but no paging/consistency options yet. */
  BatchableStatement<?> build(
      CqlSession session, String cql, List<Object> positional, Map<String, Object> named) {
    if ((positional == null || positional.isEmpty()) && (named == null || named.isEmpty())) {
      return SimpleStatement.newInstance(cql);
    }
    PreparedStatement prepared = session.prepare(cql);
    BoundStatementBuilder builder = prepared.boundStatementBuilder();
    List<ColumnDefinition> variables = allVariables(prepared);

    if (positional != null && !positional.isEmpty()) {
      if (positional.size() > variables.size()) {
        throw new CassyxCoreException(
            "Statement has " + variables.size() + " bind marker(s) but " + positional.size() + " value(s)");
      }
      for (int i = 0; i < positional.size(); i++) {
        DataType type = variables.get(i).getType();
        bind(builder, i, type, positional.get(i));
      }
    }
    if (named != null && !named.isEmpty()) {
      for (Map.Entry<String, Object> entry : named.entrySet()) {
        CqlIdentifier id = CqlIdentifier.fromInternal(entry.getKey());
        if (!prepared.getVariableDefinitions().contains(id)) {
          throw new CassyxCoreException("No bind marker named ':" + entry.getKey() + "' in this statement");
        }
        DataType type = prepared.getVariableDefinitions().get(id).getType();
        int index = indexOf(variables, entry.getKey());
        bind(builder, index, type, entry.getValue());
      }
    }
    return builder.build();
  }

  /** Index-based rather than iterator-based: bind-marker POSITION is what a `?` refers to. */
  private static List<ColumnDefinition> allVariables(PreparedStatement prepared) {
    ColumnDefinitions definitions = prepared.getVariableDefinitions();
    List<ColumnDefinition> variables = new java.util.ArrayList<>(definitions.size());
    for (int i = 0; i < definitions.size(); i++) {
      variables.add(definitions.get(i));
    }
    return variables;
  }

  private static int indexOf(List<ColumnDefinition> variables, String name) {
    for (int i = 0; i < variables.size(); i++) {
      if (variables.get(i).getName().asInternal().equals(name)) {
        return i;
      }
    }
    throw new CassyxCoreException("No bind marker named ':" + name + "' in this statement");
  }

  private void bind(BoundStatementBuilder builder, int index, DataType type, Object wire) {
    Object decoded = codec.fromWire(wire, type);
    if (decoded == CqlValueCodec.UNSET_VALUE) {
      builder.setToNull(index);
      builder.unset(index);
      return;
    }
    if (decoded == null) {
      builder.setToNull(index);
      return;
    }
    builder.set(index, decoded, javaType(type, decoded));
  }

  @SuppressWarnings("unchecked")
  private static <T> Class<T> javaType(DataType type, Object decoded) {
    return (Class<T>) decoded.getClass();
  }

  /** Applies the statement-level controls of plan section 5.1. */
  @SuppressWarnings({"unchecked", "rawtypes"})
  static Statement<?> applyOptions(
      CqlSession session,
      Statement<?> statement,
      String keyspace,
      String consistency,
      String serialConsistency,
      int fetchSize,
      java.time.Duration timeout,
      boolean tracing,
      boolean idempotent,
      List<String> warnings) {

    Statement result = statement.setPageSize(fetchSize);
    if (consistency != null && !consistency.isBlank()) {
      result = result.setConsistencyLevel(parseConsistency(consistency));
    }
    if (serialConsistency != null && !serialConsistency.isBlank()) {
      result = result.setSerialConsistencyLevel(parseSerialConsistency(serialConsistency));
    }
    if (timeout != null) {
      result = result.setTimeout(timeout);
    }
    if (tracing) {
      result = result.setTracing(true);
    }
    result = result.setIdempotent(Boolean.valueOf(idempotent));
    return applyKeyspace(session, result, keyspace, warnings);
  }

  /**
   * A per-request keyspace needs native protocol v5. On v4 the driver rejects the statement at
   * execution time, so degrade to a warning the user can act on rather than an opaque failure.
   * A bound statement already carries the keyspace of the prepared statement, so it is left alone.
   */
  private static Statement<?> applyKeyspace(
      CqlSession session, Statement<?> statement, String keyspace, List<String> warnings) {
    if (keyspace == null || keyspace.isBlank()) {
      return statement;
    }
    if (session.getContext().getProtocolVersion().getCode() < 5) {
      if (warnings != null) {
        warnings.add(
            "Per-request keyspace needs native protocol v5; qualify the statement as "
                + keyspace
                + ".<table> instead.");
      }
      return statement;
    }
    CqlIdentifier id = CqlIdentifier.fromInternal(keyspace);
    if (statement instanceof SimpleStatement simple) {
      return simple.setKeyspace(id);
    }
    if (statement instanceof BatchStatement batch) {
      return batch.setKeyspace(id);
    }
    return statement;
  }

  static ConsistencyLevel parseConsistency(String name) {
    try {
      return DefaultConsistencyLevel.valueOf(name.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new CassyxCoreException("Unknown consistency level '" + name + "'", e);
    }
  }

  static ConsistencyLevel parseSerialConsistency(String name) {
    ConsistencyLevel level = parseConsistency(name);
    if (level != DefaultConsistencyLevel.SERIAL && level != DefaultConsistencyLevel.LOCAL_SERIAL) {
      throw new CassyxCoreException(
          "Serial consistency must be SERIAL or LOCAL_SERIAL, got '" + name + "'");
    }
    return level;
  }
}
