package io.cassyx.vector.impl;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.datastax.oss.driver.api.core.metadata.schema.ColumnMetadata;
import com.datastax.oss.driver.api.core.metadata.schema.TableMetadata;
import com.datastax.oss.driver.api.core.type.VectorType;
import io.cassyx.core.api.CoreFactory;
import io.cassyx.vector.api.SaiIndexDescriptor;
import io.cassyx.vector.api.SaiIndexManager;
import io.cassyx.vector.api.SimilarityFunction;
import io.cassyx.vector.api.SimilarityScores;
import io.cassyx.vector.api.VectorCapabilities;
import io.cassyx.vector.api.VectorColumn;
import io.cassyx.vector.api.VectorColumnDefinition;
import io.cassyx.vector.api.VectorEncoding;
import io.cassyx.vector.api.VectorException;
import io.cassyx.vector.api.VectorService;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/** Reference {@link VectorService}, driven entirely by driver schema metadata. */
public final class DefaultVectorService implements VectorService {

  private final SaiIndexManager indexManager;

  public DefaultVectorService(SaiIndexManager indexManager) {
    this.indexManager = indexManager;
  }

  @Override
  public List<VectorColumn> vectorColumns(CqlSession session, String keyspace, String table) {
    TableMetadata metadata = DefaultSaiIndexManager.tableMetadata(session, keyspace, table);
    List<SaiIndexDescriptor> indexes = indexManager.list(session, keyspace, table);

    List<VectorColumn> columns = new ArrayList<>();
    for (ColumnMetadata column : metadata.getColumns().values()) {
      if (column.getType() instanceof VectorType vector) {
        String name = column.getName().asInternal();
        columns.add(
            new VectorColumn(
                keyspace,
                table,
                name,
                vector.getDimensions(),
                VectorColumn.FLOAT,
                indexes.stream()
                    .filter(index -> index.vectorIndex() && name.equals(index.target()))
                    .findFirst()
                    .orElse(null)));
      }
    }
    return List.copyOf(columns);
  }

  @Override
  public VectorColumn vectorColumn(
      CqlSession session, String keyspace, String table, String column) {
    return vectorColumns(session, keyspace, table).stream()
        .filter(candidate -> candidate.column().equals(column))
        .findFirst()
        .orElse(null);
  }

  @Override
  public List<String> addColumnCql(
      String keyspace, String table, VectorColumnDefinition definition) {
    List<String> statements = new ArrayList<>();
    statements.add(
        "ALTER TABLE "
            + CqlLiterals.qualified(keyspace, table)
            + " ADD "
            + CqlLiterals.identifier(definition.name())
            + " "
            + definition.cqlType());
    if (definition.createIndex()) {
      statements.add(
          indexManager.createIndexCql(keyspace, table, definition.toIndexDefinition(table)));
    }
    return List.copyOf(statements);
  }

  @Override
  public List<Float> readVector(
      CqlSession session,
      String keyspace,
      String table,
      String column,
      Map<String, Object> primaryKey) {
    TableMetadata metadata = DefaultSaiIndexManager.tableMetadata(session, keyspace, table);
    Map<String, Object> key = primaryKey == null ? Map.of() : new LinkedHashMap<>(primaryKey);

    // Refuse a partial key rather than returning "some row that matched": the whole point of the
    // reference-row path is that the user picked ONE row.
    List<String> missing =
        metadata.getPrimaryKey().stream()
            .map(pk -> pk.getName().asInternal())
            .filter(name -> !key.containsKey(name))
            .toList();
    if (!missing.isEmpty()) {
      throw new VectorException(
          "Incomplete primary key for " + keyspace + "." + table + "; missing " + missing);
    }

    StringJoiner where = new StringJoiner(" AND ");
    List<Object> values = new ArrayList<>();
    key.forEach(
        (name, value) -> {
          where.add(CqlLiterals.identifier(name) + " = ?");
          values.add(value);
        });

    SimpleStatement statement =
        SimpleStatement.builder(
                "SELECT "
                    + CqlLiterals.identifier(column)
                    + " FROM "
                    + CqlLiterals.qualified(keyspace, table)
                    + " WHERE "
                    + where
                    + " LIMIT 1")
            .addPositionalValues(values.toArray())
            .build();

    Row row = session.execute(statement).one();
    if (row == null) {
      throw new VectorException(
          "No row in " + keyspace + "." + table + " for the supplied primary key");
    }
    List<Float> vector = VectorEncoding.toFloatList(row.getObject(0));
    if (vector == null) {
      throw new VectorException(
          "Column " + column + " is null on the referenced row, so it cannot seed an ANN query");
    }
    return vector;
  }

  @Override
  public SimilarityScores compare(
      List<Float> left, List<Float> right, Collection<SimilarityFunction> functions) {
    float[] a = VectorEncoding.toArray(left);
    float[] b = VectorEncoding.toArray(right);
    Collection<SimilarityFunction> requested =
        functions == null || functions.isEmpty() ? List.of(SimilarityFunction.COSINE) : functions;

    Map<SimilarityFunction, Double> scores = new LinkedHashMap<>();
    for (SimilarityFunction function : requested) {
      scores.put(function, function.score(a, b));
    }
    return new SimilarityScores(
        a.length, scores, VectorEncoding.magnitude(a), VectorEncoding.magnitude(b));
  }

  @Override
  public double magnitude(List<Float> vector) {
    return VectorEncoding.magnitude(VectorEncoding.toArray(vector));
  }

  @Override
  public VectorCapabilities capabilities(CqlSession session) {
    return VectorCapabilities.from(CoreFactory.detectCapabilities(session).orElse(null));
  }
}
