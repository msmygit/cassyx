package io.cassyx.core.impl.query;

import com.datastax.oss.driver.api.core.CqlIdentifier;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.datastax.oss.driver.api.core.cql.Statement;
import com.datastax.oss.driver.api.core.metadata.schema.ColumnMetadata;
import com.datastax.oss.driver.api.core.metadata.schema.TableMetadata;
import com.datastax.oss.driver.api.core.type.DataType;
import io.cassyx.core.api.CassyxCoreException;
import io.cassyx.core.api.query.ColumnInfo;
import io.cassyx.core.api.query.CqlValueCodec;
import io.cassyx.core.api.query.EditabilityVerdict;
import io.cassyx.core.api.query.GeneratedStatements;
import io.cassyx.core.api.query.IncompletePrimaryKeyException;
import io.cassyx.core.api.query.RowDeleteSpec;
import io.cassyx.core.api.query.RowInsertSpec;
import io.cassyx.core.api.query.RowMutationOutcome;
import io.cassyx.core.api.query.RowMutationService;
import io.cassyx.core.api.query.RowUpdateSpec;
import io.cassyx.core.api.query.StatementGenerationSpec;
import io.cassyx.core.api.query.TableKeyInfo;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Row CRUD and statement generation over live driver schema metadata (plan section 7).
 *
 * <p>Every generated statement is returned to the caller whether or not it was executed, because the
 * plan is explicit that generated CQL is shown and editable before it runs - never executed silently.
 */
public final class DefaultRowMutationService implements RowMutationService {

  private final CqlValueCodec codec;

  public DefaultRowMutationService() {
    this(new DefaultCqlValueCodec());
  }

  public DefaultRowMutationService(CqlValueCodec codec) {
    this.codec = codec;
  }

  @Override
  public TableKeyInfo tableKey(CqlSession session, String keyspace, String table) {
    TableMetadata metadata = requireTable(session, keyspace, table);
    Map<String, DataType> types = new LinkedHashMap<>();
    metadata
        .getColumns()
        .forEach((id, column) -> types.put(id.asInternal(), column.getType()));
    return new TableKeyInfo(
        keyspace,
        table,
        metadata.getPartitionKey().stream().map(c -> c.getName().asInternal()).toList(),
        metadata.getClusteringColumns().keySet().stream().map(c -> c.getName().asInternal()).toList(),
        types);
  }

  @Override
  public EditabilityVerdict editability(
      CqlSession session, String keyspace, String table, List<String> projectedColumns) {
    TableKeyInfo key = tableKey(session, keyspace, table);
    Set<String> projected = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    if (projectedColumns != null) {
      projected.addAll(projectedColumns);
    }
    // `SELECT *` projects everything by definition, so treat a lone star as complete.
    if (projected.contains("*")) {
      projected.addAll(key.primaryKey());
    }
    List<String> missing = key.primaryKey().stream().filter(c -> !projected.contains(c)).toList();

    if (missing.isEmpty()) {
      return new EditabilityVerdict(
          true,
          key.primaryKey(),
          List.of(),
          "This result set projects the complete primary key of "
              + keyspace
              + "."
              + table
              + ", so rows can be edited in place.",
          null);
    }

    String missingList = String.join(", ", missing);
    String reason =
        "This result set does not project "
            + missingList
            + ", part of the primary key of "
            + keyspace
            + "."
            + table
            + ", so rows cannot be edited in place. Add "
            + (missing.size() == 1 ? "it" : "them")
            + " to the SELECT clause.";
    List<String> suggested = new ArrayList<>(key.primaryKey());
    projectedColumns.stream()
        .filter(c -> !"*".equals(c))
        .filter(c -> !suggested.contains(c))
        .forEach(suggested::add);
    String suggestedCql =
        "SELECT " + String.join(", ", suggested) + " FROM " + qualified(keyspace, table);
    return new EditabilityVerdict(false, key.primaryKey(), missing, reason, suggestedCql);
  }

  @Override
  public RowMutationOutcome insert(CqlSession session, String keyspace, String table, RowInsertSpec spec) {
    TableKeyInfo key = tableKey(session, keyspace, table);
    List<String> warnings = new ArrayList<>();
    Map<String, Object> written = writable(spec.values());
    if (written.isEmpty()) {
      throw new CassyxCoreException("An INSERT needs at least one value.");
    }
    List<String> missingKeys = key.primaryKey().stream().filter(c -> !containsIgnoreCase(written, c)).toList();
    if (!missingKeys.isEmpty()) {
      throw new IncompletePrimaryKeyException(
          "An INSERT must supply the complete primary key; missing " + String.join(", ", missingKeys) + ".",
          missingKeys);
    }

    String cql =
        insertCql(
            keyspace,
            table,
            key,
            written,
            spec.ttlSeconds(),
            spec.timestampMicros(),
            spec.ifNotExists());
    return run(session, cql, spec.previewOnly(), spec.consistency(), spec.serialConsistency(), warnings);
  }

  @Override
  public RowMutationOutcome update(CqlSession session, String keyspace, String table, RowUpdateSpec spec) {
    TableKeyInfo key = tableKey(session, keyspace, table);
    requireCompleteKey(key, spec.primaryKey().keySet());
    Map<String, Object> written = writable(spec.values());
    if (written.isEmpty()) {
      throw new CassyxCoreException("An UPDATE needs at least one column to set.");
    }
    List<String> keyColumnsInValues =
        written.keySet().stream().filter(key::isPrimaryKeyColumn).toList();
    if (!keyColumnsInValues.isEmpty()) {
      throw new CassyxCoreException(
          "Primary-key columns cannot be updated: " + String.join(", ", keyColumnsInValues)
              + ". Delete and re-insert the row instead.");
    }

    StringBuilder cql = new StringBuilder("UPDATE ").append(qualified(keyspace, table));
    cql.append(using(spec.ttlSeconds(), spec.timestampMicros()));
    cql.append(" SET ").append(assignments(key, written));
    cql.append(" WHERE ").append(whereClause(key, spec.primaryKey()));
    appendCondition(cql, spec.ifExists(), spec.condition());
    return run(
        session, cql.toString(), spec.previewOnly(), spec.consistency(), spec.serialConsistency(), new ArrayList<>());
  }

  @Override
  public RowMutationOutcome delete(CqlSession session, String keyspace, String table, RowDeleteSpec spec) {
    TableKeyInfo key = tableKey(session, keyspace, table);
    requireCompleteKey(key, spec.primaryKey().keySet());
    List<String> badColumns = spec.columns().stream().filter(key::isPrimaryKeyColumn).toList();
    if (!badColumns.isEmpty()) {
      throw new CassyxCoreException(
          "Primary-key columns cannot be deleted individually: " + String.join(", ", badColumns) + ".");
    }

    StringBuilder cql = new StringBuilder("DELETE");
    if (!spec.columns().isEmpty()) {
      cql.append(' ').append(spec.columns().stream().map(DefaultRowMutationService::quoteId)
          .collect(Collectors.joining(", ")));
    }
    cql.append(" FROM ").append(qualified(keyspace, table));
    if (spec.timestampMicros() != null) {
      cql.append(" USING TIMESTAMP ").append(spec.timestampMicros());
    }
    cql.append(" WHERE ").append(whereClause(key, spec.primaryKey()));
    appendCondition(cql, spec.ifExists(), spec.condition());
    return run(session, cql.toString(), spec.previewOnly(), spec.consistency(), null, new ArrayList<>());
  }

  @Override
  public GeneratedStatements generate(
      CqlSession session, String keyspace, String table, StatementGenerationSpec spec) {
    TableKeyInfo key = tableKey(session, keyspace, table);
    List<String> warnings = new ArrayList<>();
    List<String> out = new ArrayList<>();

    for (Map<String, Object> row : spec.rows()) {
      Map<String, Object> filtered = restrict(row, spec.columns());
      switch (spec.kind()) {
        case INSERT -> out.add(
            insertCql(
                    keyspace,
                    table,
                    key,
                    writable(filtered),
                    spec.ttlSeconds(),
                    spec.timestampMicros(),
                    spec.includeIfConditions())
                + ";");
        case UPDATE -> out.add(generateUpdate(keyspace, table, key, row, filtered, spec) + ";");
        case DELETE -> out.add(generateDelete(keyspace, table, key, row, spec) + ";");
        default -> throw new CassyxCoreException("Unsupported statement kind " + spec.kind());
      }
    }

    String joined;
    if (spec.asBatch() && !out.isEmpty()) {
      joined =
          "BEGIN BATCH\n  "
              + String.join("\n  ", out)
              + "\nAPPLY BATCH;";
      if (spec.kind() == StatementGenerationSpec.Kind.INSERT && spec.rows().size() > 1) {
        warnings.add(
            "A batch across multiple partitions costs the coordinator more than the same writes "
                + "issued in parallel. Batch only rows that share a partition key.");
      }
    } else {
      joined = String.join(spec.formatted() ? "\n" : " ", out);
    }
    return new GeneratedStatements(out, joined, spec.rows().size(), warnings);
  }

  private String generateUpdate(
      String keyspace,
      String table,
      TableKeyInfo key,
      Map<String, Object> row,
      Map<String, Object> filtered,
      StatementGenerationSpec spec) {
    requireCompleteKey(key, row.keySet());
    Map<String, Object> primaryKey = new LinkedHashMap<>();
    key.primaryKey().forEach(c -> primaryKey.put(c, valueOf(row, c)));
    Map<String, Object> values = writable(filtered);
    key.primaryKey().forEach(values::remove);
    if (values.isEmpty()) {
      throw new CassyxCoreException("An UPDATE needs at least one non-primary-key column to set.");
    }
    StringBuilder cql = new StringBuilder("UPDATE ").append(qualified(keyspace, table));
    cql.append(using(spec.ttlSeconds(), spec.timestampMicros()));
    cql.append(" SET ").append(assignments(key, values));
    cql.append(" WHERE ").append(whereClause(key, primaryKey));
    appendCondition(cql, spec.includeIfConditions(), null);
    return cql.toString();
  }

  private String generateDelete(
      String keyspace, String table, TableKeyInfo key, Map<String, Object> row, StatementGenerationSpec spec) {
    requireCompleteKey(key, row.keySet());
    Map<String, Object> primaryKey = new LinkedHashMap<>();
    key.primaryKey().forEach(c -> primaryKey.put(c, valueOf(row, c)));
    StringBuilder cql = new StringBuilder("DELETE FROM ").append(qualified(keyspace, table));
    if (spec.timestampMicros() != null) {
      cql.append(" USING TIMESTAMP ").append(spec.timestampMicros());
    }
    cql.append(" WHERE ").append(whereClause(key, primaryKey));
    appendCondition(cql, spec.includeIfConditions(), null);
    return cql.toString();
  }

  /* ------------------------------------------------------------------ statement construction */

  private String insertCql(
      String keyspace,
      String table,
      TableKeyInfo key,
      Map<String, Object> values,
      Integer ttlSeconds,
      Long timestampMicros,
      boolean ifNotExists) {

    List<String> names = new ArrayList<>();
    List<String> literals = new ArrayList<>();
    values.forEach(
        (column, value) -> {
          names.add(quoteId(column));
          literals.add(codec.toLiteral(value, typeOf(key, column)));
        });
    StringBuilder cql =
        new StringBuilder("INSERT INTO ")
            .append(qualified(keyspace, table))
            .append(" (")
            .append(String.join(", ", names))
            .append(") VALUES (")
            .append(String.join(", ", literals))
            .append(')');
    if (ifNotExists) {
      cql.append(" IF NOT EXISTS");
    }
    cql.append(using(ttlSeconds, timestampMicros));
    return cql.toString();
  }

  private String assignments(TableKeyInfo key, Map<String, Object> values) {
    return values.entrySet().stream()
        .map(e -> quoteId(e.getKey()) + " = " + codec.toLiteral(e.getValue(), typeOf(key, e.getKey())))
        .collect(Collectors.joining(", "));
  }

  private String whereClause(TableKeyInfo key, Map<String, Object> primaryKey) {
    return key.primaryKey().stream()
        .map(
            column ->
                quoteId(column) + " = " + codec.toLiteral(valueOf(primaryKey, column), typeOf(key, column)))
        .collect(Collectors.joining(" AND "));
  }

  private static void appendCondition(StringBuilder cql, boolean ifExists, String condition) {
    if (condition != null && !condition.isBlank()) {
      String trimmed = condition.strip();
      cql.append(" IF ").append(trimmed.toUpperCase(java.util.Locale.ROOT).startsWith("IF ")
          ? trimmed.substring(3).strip() : trimmed);
    } else if (ifExists) {
      cql.append(" IF EXISTS");
    }
  }

  private static String using(Integer ttlSeconds, Long timestampMicros) {
    List<String> parts = new ArrayList<>();
    if (ttlSeconds != null && ttlSeconds > 0) {
      parts.add("TTL " + ttlSeconds);
    }
    if (timestampMicros != null) {
      parts.add("TIMESTAMP " + timestampMicros);
    }
    return parts.isEmpty() ? "" : " USING " + String.join(" AND ", parts);
  }

  /* ------------------------------------------------------------------------------ execution */

  private RowMutationOutcome run(
      CqlSession session,
      String cql,
      boolean previewOnly,
      String consistency,
      String serialConsistency,
      List<String> warnings) {

    if (previewOnly) {
      return new RowMutationOutcome(false, cql, null, null, 0L, warnings);
    }
    Statement<?> statement = SimpleStatement.newInstance(cql);
    if (consistency != null && !consistency.isBlank()) {
      statement = statement.setConsistencyLevel(StatementFactory.parseConsistency(consistency));
    }
    if (serialConsistency != null && !serialConsistency.isBlank()) {
      statement = statement.setSerialConsistencyLevel(StatementFactory.parseSerialConsistency(serialConsistency));
    }

    long started = System.nanoTime();
    ResultSet rs = session.execute(statement);
    long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    warnings.addAll(rs.getExecutionInfo().getWarnings());

    boolean conditional = rs.getColumnDefinitions().contains("[applied]");
    Boolean applied = conditional ? Boolean.valueOf(rs.wasApplied()) : null;
    Map<String, Object> current = null;
    if (conditional && Boolean.FALSE.equals(applied)) {
      Row row = rs.one();
      if (row != null) {
        current = new LinkedHashMap<>();
        for (int i = 0; i < row.getColumnDefinitions().size(); i++) {
          String name = row.getColumnDefinitions().get(i).getName().asInternal();
          if (!"[applied]".equals(name)) {
            current.put(name, codec.toWire(row.getObject(i)));
          }
        }
      }
    }
    return new RowMutationOutcome(true, cql, applied, current, elapsed, warnings);
  }

  /* -------------------------------------------------------------------------------- helpers */

  private static TableMetadata requireTable(CqlSession session, String keyspace, String table) {
    return session
        .getMetadata()
        .getKeyspace(CqlIdentifier.fromInternal(keyspace))
        .flatMap(ks -> ks.getTable(CqlIdentifier.fromInternal(table)))
        .orElseThrow(
            () -> new CassyxCoreException("No table " + keyspace + "." + table + " in this cluster."));
  }

  private static void requireCompleteKey(TableKeyInfo key, Set<String> supplied) {
    Set<String> present = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    present.addAll(supplied);
    List<String> missing = key.primaryKey().stream().filter(c -> !present.contains(c)).toList();
    if (!missing.isEmpty()) {
      throw new IncompletePrimaryKeyException(
          "This operation needs the complete primary key of "
              + key.keyspace()
              + "."
              + key.table()
              + "; missing "
              + String.join(", ", missing)
              + ".",
          missing);
    }
  }

  /** Drops unset entries: an unset column is not written at all, unlike an explicit null. */
  private static Map<String, Object> writable(Map<String, Object> values) {
    Map<String, Object> out = new LinkedHashMap<>();
    values.forEach(
        (column, value) -> {
          if (!CqlValueCodec.isUnset(value)) {
            out.put(column, value);
          }
        });
    return out;
  }

  private static Map<String, Object> restrict(Map<String, Object> row, List<String> columns) {
    if (columns == null || columns.isEmpty()) {
      return row;
    }
    Map<String, Object> out = new LinkedHashMap<>();
    columns.forEach(
        column -> {
          if (row.containsKey(column)) {
            out.put(column, row.get(column));
          }
        });
    return out;
  }

  private static boolean containsIgnoreCase(Map<String, Object> map, String key) {
    return map.keySet().stream().anyMatch(k -> k.equalsIgnoreCase(key));
  }

  private static Object valueOf(Map<String, Object> map, String key) {
    if (map.containsKey(key)) {
      return map.get(key);
    }
    return map.entrySet().stream()
        .filter(e -> e.getKey().equalsIgnoreCase(key))
        .map(Map.Entry::getValue)
        .findFirst()
        .orElse(null);
  }

  private static DataType typeOf(TableKeyInfo key, String column) {
    DataType type = key.columnTypes().get(column);
    if (type != null) {
      return type;
    }
    return key.columnTypes().entrySet().stream()
        .filter(e -> e.getKey().equalsIgnoreCase(column))
        .map(Map.Entry::getValue)
        .findFirst()
        .orElseThrow(
            () ->
                new CassyxCoreException(
                    "No column " + column + " on " + key.keyspace() + "." + key.table() + "."));
  }

  private static String qualified(String keyspace, String table) {
    return quoteId(keyspace) + "." + quoteId(table);
  }

  /** Re-quotes case-sensitive identifiers; unquoted ones stay unquoted so the CQL stays readable. */
  static String quoteId(String identifier) {
    return CqlIdentifier.fromInternal(identifier).asCql(true);
  }

  /** Column names of a result set, for the editability check. */
  static List<String> namesOf(List<ColumnInfo> columns) {
    return columns.stream().map(ColumnInfo::name).toList();
  }

  /** Exposed so the API layer can report a column's declared kind without a second metadata read. */
  static String kindOf(TableMetadata table, ColumnMetadata column) {
    if (table.getPartitionKey().contains(column)) {
      return "PARTITION_KEY";
    }
    if (table.getClusteringColumns().containsKey(column)) {
      return "CLUSTERING";
    }
    return column.isStatic() ? "STATIC" : "REGULAR";
  }
}
