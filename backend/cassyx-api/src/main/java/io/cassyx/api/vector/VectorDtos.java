package io.cassyx.api.vector;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.cassyx.vector.api.AnnQueryPreview;
import io.cassyx.vector.api.SaiIndexDescriptor;
import io.cassyx.vector.api.SaiIndexStatus;
import io.cassyx.vector.api.SimilarityFunction;
import io.cassyx.vector.api.SimilarityScores;
import io.cassyx.vector.api.VectorColumn;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Wire shapes for the {@code vector} tag of {@code openapi/cassyx-api.yaml}.
 *
 * <p>These mirror the contract's schemas exactly - {@code VectorColumn}, {@code SaiIndex},
 * {@code AnnQueryRequest} and friends. They are deliberately separate from the cassyx-vector
 * domain records: the module must stay usable without the web layer, and the contract must be able
 * to evolve its JSON without dragging the library API with it (plan sections 2.1, 2.3).
 *
 * <p>NOTE for the orchestrator: {@code SchemaIdentity}, {@code DdlExecutionResult} and
 * {@code QueryResult} are shared schemas that workstreams B, C and G also render. They are declared
 * here in this package to avoid editing another workstream's files; once one owner publishes a
 * canonical Java rendering, these three should be replaced by it. The JSON shape is the contract's
 * either way, so this is a de-duplication task, not a compatibility one.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class VectorDtos {

  private VectorDtos() {}

  /* ------------------------------------------------------------------ shared schemas */

  /** Contract {@code SchemaIdentity}. */
  public record SchemaIdentity(
      String kind,
      String keyspace,
      String table,
      String column,
      String index,
      String qualifiedName) {

    public static SchemaIdentity column(String keyspace, String table, String column) {
      return new SchemaIdentity(
          "COLUMN", keyspace, table, column, null, keyspace + "." + table + "." + column);
    }

    public static SchemaIdentity index(String keyspace, String table, String index) {
      return new SchemaIdentity(
          "INDEX", keyspace, table, null, index, keyspace + "." + index);
    }
  }

  /** Contract {@code DdlExecutionResult}. */
  public record DdlExecutionResult(
      boolean success,
      List<String> executedCql,
      int statementsExecuted,
      long elapsedMillis,
      boolean schemaAgreement,
      List<String> warnings,
      SchemaIdentity affectedIdentity) {}

  /** Contract {@code ColumnMetadata} - the subset an ANN result set needs. */
  public record ColumnMetadata(
      String name,
      String type,
      String keyspace,
      String table,
      boolean primaryKeyColumn,
      boolean collection,
      boolean vector,
      Integer vectorDimensions,
      boolean udt,
      boolean similarity) {}

  /** Contract {@code QueryResult}. */
  public record QueryResult(
      String resultHandle,
      String queryId,
      List<ColumnMetadata> columns,
      List<Map<String, Object>> rows,
      int rowCount,
      int pageNumber,
      boolean hasMorePages,
      String nextPageToken,
      String previousPageToken,
      Boolean applied,
      boolean wasVoid,
      long elapsedMillis,
      List<String> warnings,
      List<String> similarityColumns,
      String coordinator,
      String consistency) {}

  /* ----------------------------------------------------------------- vector schemas */

  /** Contract {@code VectorColumn}. */
  public record VectorColumnResponse(
      SchemaIdentity identity,
      String name,
      int dimensions,
      String elementType,
      String cqlType,
      SaiIndexResponse index,
      String similarityFunction) {

    public static VectorColumnResponse from(VectorColumn column) {
      return new VectorColumnResponse(
          SchemaIdentity.column(column.keyspace(), column.table(), column.column()),
          column.column(),
          column.dimensions(),
          column.elementType(),
          column.cqlType(),
          column.index() == null ? null : SaiIndexResponse.from(column.index()),
          column.similarityFunction() == null ? null : column.similarityFunction().cqlValue());
    }
  }

  /** Contract {@code VectorColumnDefinition}. */
  public record VectorColumnDefinitionRequest(
      String name,
      Integer dimensions,
      String elementType,
      Boolean createIndex,
      String indexName,
      String similarityFunction,
      String sourceModel) {}

  /** Contract {@code SaiIndex}. */
  public record SaiIndexResponse(
      SchemaIdentity identity,
      String name,
      String target,
      SchemaIdentity targetColumn,
      boolean vectorIndex,
      String similarityFunction,
      String sourceModel,
      Map<String, String> options,
      String className) {

    public static SaiIndexResponse from(SaiIndexDescriptor index) {
      return new SaiIndexResponse(
          SchemaIdentity.index(index.keyspace(), index.table(), index.name()),
          index.name(),
          index.target(),
          SchemaIdentity.column(index.keyspace(), index.table(), index.target()),
          index.vectorIndex(),
          index.similarityFunction() == null ? null : index.similarityFunction().cqlValue(),
          index.sourceModel(),
          index.options(),
          index.className());
    }
  }

  /** Contract {@code SaiIndexDefinition}. */
  public record SaiIndexDefinitionRequest(
      String name,
      String target,
      String similarityFunction,
      String sourceModel,
      Boolean caseSensitive,
      Boolean normalize,
      Boolean asciiOnly,
      String analyzer,
      Map<String, String> options,
      Boolean ifNotExists) {}

  /** Contract {@code SaiIndexNodeStatus}. */
  public record SaiIndexNodeStatusResponse(String endpoint, String state) {}

  /** Contract {@code SaiIndexStatus}. */
  public record SaiIndexStatusResponse(
      SchemaIdentity identity,
      String name,
      String state,
      Double buildProgressPercent,
      boolean queryable,
      List<SaiIndexNodeStatusResponse> perNode,
      SaiIndexResponse definition) {

    public static SaiIndexStatusResponse from(SaiIndexStatus status) {
      return new SaiIndexStatusResponse(
          SchemaIdentity.index(status.keyspace(), status.table(), status.name()),
          status.name(),
          status.state().name(),
          status.buildProgressPercent(),
          status.queryable(),
          status.perNode().stream()
              .map(node -> new SaiIndexNodeStatusResponse(node.endpoint(), node.state().name()))
              .toList(),
          status.definition() == null ? null : SaiIndexResponse.from(status.definition()));
    }
  }

  /** Contract {@code AnnQueryRowReference}. */
  public record AnnQueryRowReference(Map<String, Object> primaryKey, String column) {}

  /** Contract {@code AnnQueryVectorSource} - exactly one of the three is set. */
  public record AnnQueryVectorSource(
      List<Float> values, AnnQueryRowReference fromRow, String uploadId) {}

  /** Contract {@code AnnPredicate}. */
  public record AnnPredicateRequest(String column, String operator, Object value) {}

  /** Contract {@code AnnQueryRequest}. */
  public record AnnQueryRequest(
      String keyspace,
      String table,
      String vectorColumn,
      AnnQueryVectorSource queryVector,
      Integer limit,
      List<String> selectColumns,
      List<AnnPredicateRequest> predicates,
      List<String> similarityProjections,
      Boolean includeVectorColumn,
      String consistency,
      Integer fetchSize) {}

  /** Contract {@code AnnQueryPreview}. */
  public record AnnQueryPreviewResponse(
      String cql,
      String abbreviatedCql,
      int dimensions,
      List<String> similarityColumns,
      List<String> warnings,
      SaiIndexResponse indexUsed) {

    public static AnnQueryPreviewResponse from(AnnQueryPreview preview) {
      return new AnnQueryPreviewResponse(
          preview.cql(),
          preview.abbreviatedCql(),
          preview.dimensions(),
          preview.similarityColumns(),
          preview.warnings(),
          preview.indexUsed() == null ? null : SaiIndexResponse.from(preview.indexUsed()));
    }
  }

  /** Contract {@code SimilarityRequest}. */
  public record SimilarityRequest(
      AnnQueryVectorSource left,
      AnnQueryVectorSource right,
      List<String> functions,
      String keyspace,
      String table) {}

  /** Contract {@code SimilarityResult}. */
  public record SimilarityResult(
      int dimensions,
      Map<String, Double> scores,
      double leftMagnitude,
      double rightMagnitude,
      List<String> warnings) {

    public static SimilarityResult from(SimilarityScores scores, List<String> warnings) {
      Map<String, Double> byName = new LinkedHashMap<>(scores.scoresByCqlName());
      return new SimilarityResult(
          scores.dimensions(),
          byName,
          scores.leftMagnitude(),
          scores.rightMagnitude(),
          warnings == null ? List.of() : List.copyOf(warnings));
    }
  }

  /** Parses the contract's lowercase similarity-function names. */
  public static SimilarityFunction similarityFunction(String value) {
    return value == null ? null : SimilarityFunction.fromCql(value);
  }
}
