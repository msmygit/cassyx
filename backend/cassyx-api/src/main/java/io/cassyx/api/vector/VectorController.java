package io.cassyx.api.vector;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.ColumnDefinition;
import com.datastax.oss.driver.api.core.cql.ExecutionInfo;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.datastax.oss.driver.api.core.servererrors.QueryValidationException;
import com.datastax.oss.driver.api.core.type.DataType;
import com.datastax.oss.driver.api.core.type.VectorType;
import io.cassyx.core.api.Capability;
import io.cassyx.vector.api.AnnPredicate;
import io.cassyx.vector.api.AnnQuery;
import io.cassyx.vector.api.AnnQueryBuilder;
import io.cassyx.vector.api.SaiIndexDefinition;
import io.cassyx.vector.api.SaiIndexDescriptor;
import io.cassyx.vector.api.SaiIndexManager;
import io.cassyx.vector.api.SimilarityFunction;
import io.cassyx.vector.api.VectorCapabilities;
import io.cassyx.vector.api.VectorColumn;
import io.cassyx.vector.api.VectorColumnDefinition;
import io.cassyx.vector.api.VectorEncoding;
import io.cassyx.vector.api.VectorException;
import io.cassyx.vector.api.VectorService;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The {@code vector} tag of {@code openapi/cassyx-api.yaml} — plan section 6.
 *
 * <p>A thin adapter: every decision lives in cassyx-vector, which is plain Java and needs only a
 * {@code CqlSession} (plan section 2.1). This class resolves the session, enforces the section 7.1
 * capability gate, and translates value objects to the contract's JSON.
 *
 * <p><b>DDL is never executed silently.</b> Every generating endpoint returns the exact statements
 * it ran in {@code executedCql}, and {@code buildAnnQuery} generates without executing at all.
 */
@RestController
@RequestMapping("/api/connections/{connectionId}")
public class VectorController {

  /** Schema changes are slow; the driver's default request timeout is not sized for DDL. */
  private static final Duration DDL_TIMEOUT = Duration.ofSeconds(60);

  private static final String PROBLEM_BASE = "https://cassyx.dev/problems/";

  private final VectorSessionResolver sessions;
  private final VectorService vectors;
  private final SaiIndexManager indexes;
  private final AnnQueryBuilder annQueries;

  public VectorController(
      VectorSessionResolver sessions,
      VectorService vectors,
      SaiIndexManager indexes,
      AnnQueryBuilder annQueries) {
    this.sessions = sessions;
    this.vectors = vectors;
    this.indexes = indexes;
    this.annQueries = annQueries;
  }

  /* --------------------------------------------------------------- vector columns */

  @GetMapping("/keyspaces/{keyspace}/tables/{table}/vector-columns")
  public List<VectorDtos.VectorColumnResponse> listVectorColumns(
      @PathVariable String connectionId,
      @PathVariable String keyspace,
      @PathVariable String table) {
    CqlSession session = require(connectionId, Capability.VECTOR_ANN);
    return vectors.vectorColumns(session, keyspace, table).stream()
        .map(VectorDtos.VectorColumnResponse::from)
        .toList();
  }

  @PostMapping("/keyspaces/{keyspace}/tables/{table}/vector-columns")
  public ResponseEntity<VectorDtos.DdlExecutionResult> addVectorColumn(
      @PathVariable String connectionId,
      @PathVariable String keyspace,
      @PathVariable String table,
      @RequestBody VectorDtos.VectorColumnDefinitionRequest request) {
    CqlSession session = require(connectionId, Capability.VECTOR_ANN);

    VectorColumnDefinition definition =
        new VectorColumnDefinition(
            request.name(),
            request.dimensions() == null ? 0 : request.dimensions(),
            request.elementType(),
            Boolean.TRUE.equals(request.createIndex()),
            request.indexName(),
            VectorDtos.similarityFunction(request.similarityFunction()),
            request.sourceModel());

    VectorDtos.DdlExecutionResult result =
        execute(
            session,
            vectors.addColumnCql(keyspace, table, definition),
            VectorDtos.SchemaIdentity.column(keyspace, table, definition.name()));
    return ResponseEntity.status(HttpStatus.CREATED).body(result);
  }

  /* ------------------------------------------------------------------ SAI indexes */

  @GetMapping("/keyspaces/{keyspace}/tables/{table}/sai-indexes")
  public List<VectorDtos.SaiIndexResponse> listSaiIndexes(
      @PathVariable String connectionId,
      @PathVariable String keyspace,
      @PathVariable String table) {
    CqlSession session = require(connectionId, Capability.SAI);
    return indexes.list(session, keyspace, table).stream()
        .map(VectorDtos.SaiIndexResponse::from)
        .toList();
  }

  @PostMapping("/keyspaces/{keyspace}/tables/{table}/sai-indexes")
  public ResponseEntity<VectorDtos.DdlExecutionResult> createSaiIndex(
      @PathVariable String connectionId,
      @PathVariable String keyspace,
      @PathVariable String table,
      @RequestBody VectorDtos.SaiIndexDefinitionRequest request) {
    CqlSession session = require(connectionId, Capability.SAI);
    SaiIndexDefinition definition = toDefinition(request);

    VectorDtos.DdlExecutionResult result =
        execute(
            session,
            List.of(indexes.createIndexCql(keyspace, table, definition)),
            VectorDtos.SchemaIdentity.index(keyspace, table, definition.name()));
    return ResponseEntity.status(HttpStatus.CREATED).body(result);
  }

  @GetMapping("/keyspaces/{keyspace}/tables/{table}/sai-indexes/{index}")
  public VectorDtos.SaiIndexStatusResponse getSaiIndexStatus(
      @PathVariable String connectionId,
      @PathVariable String keyspace,
      @PathVariable String table,
      @PathVariable String index) {
    CqlSession session = require(connectionId, Capability.SAI);
    return VectorDtos.SaiIndexStatusResponse.from(
        indexes.status(session, keyspace, table, index));
  }

  /**
   * Cassandra has no {@code ALTER INDEX}; this runs the generated drop-and-recreate pair and
   * returns both statements, so the user sees exactly what happened.
   */
  @PutMapping("/keyspaces/{keyspace}/tables/{table}/sai-indexes/{index}")
  public VectorDtos.DdlExecutionResult alterSaiIndex(
      @PathVariable String connectionId,
      @PathVariable String keyspace,
      @PathVariable String table,
      @PathVariable String index,
      @RequestBody VectorDtos.SaiIndexDefinitionRequest request) {
    CqlSession session = require(connectionId, Capability.SAI);
    SaiIndexDefinition definition = toDefinition(request, index);

    return execute(
        session,
        indexes.alterIndexCql(keyspace, table, definition),
        VectorDtos.SchemaIdentity.index(keyspace, table, index));
  }

  @DeleteMapping("/keyspaces/{keyspace}/tables/{table}/sai-indexes/{index}")
  public VectorDtos.DdlExecutionResult dropSaiIndex(
      @PathVariable String connectionId,
      @PathVariable String keyspace,
      @PathVariable String table,
      @PathVariable String index,
      @RequestParam(name = "ifExists", defaultValue = "true") boolean ifExists) {
    CqlSession session = require(connectionId, Capability.SAI);
    return execute(
        session,
        List.of(indexes.dropIndexCql(keyspace, index, ifExists)),
        VectorDtos.SchemaIdentity.index(keyspace, table, index));
  }

  /* -------------------------------------------------------------------- ANN query */

  @PostMapping("/vector/ann-query")
  public VectorDtos.AnnQueryPreviewResponse buildAnnQuery(
      @PathVariable String connectionId, @RequestBody VectorDtos.AnnQueryRequest request) {
    CqlSession session = require(connectionId, Capability.VECTOR_ANN);
    return VectorDtos.AnnQueryPreviewResponse.from(preview(session, request));
  }

  @PostMapping("/vector/ann-query/execute")
  public VectorDtos.QueryResult executeAnnQuery(
      @PathVariable String connectionId, @RequestBody VectorDtos.AnnQueryRequest request) {
    CqlSession session = require(connectionId, Capability.VECTOR_ANN);
    AnnQuery query = toQuery(session, request);

    SimpleStatement statement = annQueries.statement(query);
    // ANN returns at most k rows, so one page always suffices - which is why this result carries no
    // paging token. Paging proper is workstream C's; nothing here registers a result handle there.
    int fetchSize = Math.max(query.limit(), request.fetchSize() == null ? 500 : request.fetchSize());
    statement = statement.setPageSize(fetchSize);
    if (request.consistency() != null && !request.consistency().isBlank()) {
      statement =
          statement.setConsistencyLevel(
              com.datastax.oss.driver.api.core.DefaultConsistencyLevel.valueOf(
                  request.consistency()));
    }

    long started = System.nanoTime();
    ResultSet resultSet = session.execute(statement);
    long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;

    return toQueryResult(query, resultSet, elapsedMillis);
  }

  /* ------------------------------------------------------------------- similarity */

  @PostMapping("/vector/similarity")
  public VectorDtos.SimilarityResult computeSimilarity(
      @PathVariable String connectionId, @RequestBody VectorDtos.SimilarityRequest request) {
    CqlSession session = sessions.resolve(connectionId);

    List<String> warnings = new ArrayList<>();
    List<Float> left =
        resolveVector(session, request.left(), request.keyspace(), request.table(), null);
    List<Float> right =
        resolveVector(session, request.right(), request.keyspace(), request.table(), null);

    List<SimilarityFunction> functions = new ArrayList<>();
    if (request.functions() == null || request.functions().isEmpty()) {
      functions.add(SimilarityFunction.COSINE);
    } else {
      request.functions().forEach(name -> functions.add(SimilarityFunction.fromCql(name)));
    }

    return VectorDtos.SimilarityResult.from(vectors.compare(left, right, functions), warnings);
  }

  /* -------------------------------------------------------------------- internals */

  private io.cassyx.vector.api.AnnQueryPreview preview(
      CqlSession session, VectorDtos.AnnQueryRequest request) {
    AnnQuery query = toQuery(session, request);
    Set<String> indexed =
        indexes.list(session, request.keyspace(), request.table()).stream()
            .map(SaiIndexDescriptor::target)
            .collect(java.util.stream.Collectors.toCollection(HashSet::new));
    return annQueries.preview(query, indexed);
  }

  private AnnQuery toQuery(CqlSession session, VectorDtos.AnnQueryRequest request) {
    VectorColumn column =
        vectors.vectorColumn(session, request.keyspace(), request.table(), request.vectorColumn());
    if (column == null) {
      throw new VectorException(
          "Column "
              + request.keyspace()
              + "."
              + request.table()
              + "."
              + request.vectorColumn()
              + " is not a vector<float, N> column");
    }

    List<Float> queryVector =
        resolveVector(
            session,
            request.queryVector(),
            request.keyspace(),
            request.table(),
            request.vectorColumn());

    AnnQuery.Builder builder =
        AnnQuery.builder(column, queryVector)
            .limit(request.limit() == null ? AnnQuery.DEFAULT_LIMIT : request.limit())
            .select(request.selectColumns())
            .includeVectorColumn(Boolean.TRUE.equals(request.includeVectorColumn()));

    if (request.predicates() != null) {
      request
          .predicates()
          .forEach(
              predicate ->
                  builder.where(
                      new AnnPredicate(
                          predicate.column(), predicate.operator(), predicate.value())));
    }
    if (request.similarityProjections() != null) {
      request.similarityProjections().forEach(name -> builder.score(SimilarityFunction.fromCql(name)));
    }
    return builder.build();
  }

  /**
   * Resolves the three {@code AnnQueryVectorSource} forms. {@code uploadId} is rejected until the
   * upload endpoint exists — returning an empty vector would produce a statement that runs and
   * silently ranks against nothing.
   */
  private List<Float> resolveVector(
      CqlSession session,
      VectorDtos.AnnQueryVectorSource source,
      String keyspace,
      String table,
      String defaultColumn) {
    if (source == null) {
      throw new VectorException("A query vector is required");
    }
    if (source.values() != null && !source.values().isEmpty()) {
      return source.values();
    }
    if (source.fromRow() != null) {
      String column = source.fromRow().column() == null ? defaultColumn : source.fromRow().column();
      if (keyspace == null || table == null || column == null) {
        throw new VectorException(
            "A row reference needs keyspace, table and the vector column to read from");
      }
      return vectors.readVector(session, keyspace, table, column, source.fromRow().primaryKey());
    }
    if (source.uploadId() != null) {
      throw new VectorException(
          "Vector file upload is not wired up yet; paste the values or reference a row instead");
    }
    throw new VectorException("Exactly one of values, fromRow or uploadId must be supplied");
  }

  private SaiIndexDefinition toDefinition(VectorDtos.SaiIndexDefinitionRequest request) {
    return toDefinition(request, request.name());
  }

  private SaiIndexDefinition toDefinition(
      VectorDtos.SaiIndexDefinitionRequest request, String name) {
    return SaiIndexDefinition.builder(name, request.target())
        .similarityFunction(VectorDtos.similarityFunction(request.similarityFunction()))
        .sourceModel(request.sourceModel())
        .caseSensitive(request.caseSensitive())
        .normalize(request.normalize())
        .asciiOnly(request.asciiOnly())
        .analyzer(request.analyzer())
        .options(request.options())
        .ifNotExists(!Boolean.FALSE.equals(request.ifNotExists()))
        .build();
  }

  private VectorDtos.DdlExecutionResult execute(
      CqlSession session, List<String> statements, VectorDtos.SchemaIdentity identity) {
    long started = System.nanoTime();
    List<String> warnings = new ArrayList<>();
    boolean agreement = true;

    for (String cql : statements) {
      ResultSet result =
          session.execute(SimpleStatement.builder(cql).setTimeout(DDL_TIMEOUT).build());
      ExecutionInfo info = result.getExecutionInfo();
      warnings.addAll(info.getWarnings());
      agreement = agreement && info.isSchemaInAgreement();
    }

    return new VectorDtos.DdlExecutionResult(
        true,
        List.copyOf(statements),
        statements.size(),
        (System.nanoTime() - started) / 1_000_000L,
        agreement,
        List.copyOf(warnings),
        identity);
  }

  private VectorDtos.QueryResult toQueryResult(
      AnnQuery query, ResultSet resultSet, long elapsedMillis) {
    Set<String> similarityColumns = new HashSet<>(query.similarityColumnNames());

    List<VectorDtos.ColumnMetadata> columns = new ArrayList<>();
    for (ColumnDefinition definition : resultSet.getColumnDefinitions()) {
      String name = definition.getName().asInternal();
      DataType type = definition.getType();
      columns.add(
          new VectorDtos.ColumnMetadata(
              name,
              type.asCql(true, false),
              definition.getKeyspace().asInternal(),
              definition.getTable().asInternal(),
              false,
              type instanceof com.datastax.oss.driver.api.core.type.ContainerType
                  && !(type instanceof VectorType),
              type instanceof VectorType,
              type instanceof VectorType vector ? vector.getDimensions() : null,
              type instanceof com.datastax.oss.driver.api.core.type.UserDefinedType,
              similarityColumns.contains(name)));
    }

    List<Map<String, Object>> rows = new ArrayList<>();
    for (Row row : resultSet) {
      Map<String, Object> values = new LinkedHashMap<>();
      for (VectorDtos.ColumnMetadata column : columns) {
        Object value = row.getObject(column.name());
        // Vectors go out as JSON arrays of numbers, never as 1536 comma-separated floats in a
        // string (plan section 6, Display/Export).
        values.put(column.name(), column.vector() ? VectorEncoding.toFloatList(value) : value);
      }
      rows.add(values);
    }

    ExecutionInfo info = resultSet.getExecutionInfo();
    return new VectorDtos.QueryResult(
        "ann_" + UUID.randomUUID(),
        UUID.randomUUID().toString(),
        columns,
        rows,
        rows.size(),
        1,
        false,
        null,
        null,
        null,
        false,
        elapsedMillis,
        List.copyOf(info.getWarnings()),
        query.similarityColumnNames(),
        info.getCoordinator() == null ? null : info.getCoordinator().getEndPoint().toString(),
        info.getStatement().getConsistencyLevel() == null
            ? null
            : info.getStatement().getConsistencyLevel().name());
  }

  /**
   * Section 7.1 gate. Vector/ANN exists on Cassandra 5.x and Astra; SAI additionally on DSE 6.8+;
   * NEITHER on Amazon Keyspaces or ScyllaDB. The UI hides these features behind a tooltip; this
   * exists so a direct API call still fails legibly rather than producing a syntax error from the
   * cluster.
   */
  private CqlSession require(String connectionId, Capability capability) {
    CqlSession session = sessions.resolve(connectionId);
    VectorCapabilities capabilities = vectors.capabilities(session);
    if (!capabilities.supports(capability)) {
      throw new CapabilityUnsupportedException(capability, capabilities.explain(capability));
    }
    return session;
  }

  /* -------------------------------------------------------------- error responses */

  @ExceptionHandler(VectorSessionResolver.NoLiveSessionException.class)
  ProblemDetail notConnected(VectorSessionResolver.NoLiveSessionException e) {
    return problem(HttpStatus.CONFLICT, "not-connected", "Not connected", e.getMessage());
  }

  @ExceptionHandler(CapabilityUnsupportedException.class)
  ProblemDetail unsupported(CapabilityUnsupportedException e) {
    ProblemDetail detail =
        problem(
            HttpStatus.NOT_IMPLEMENTED,
            "capability-unsupported",
            "Unsupported on this cluster",
            e.getMessage());
    // The contract's CapabilityName values are `sai` and `vector`, not the Java constant names.
    // Workstream A/B are adding Capability.wireName() for exactly this; switch to it once
    // cassyx-core settles, and delete this mapping.
    detail.setProperty("capability", e.capability() == Capability.SAI ? "sai" : "vector");
    return detail;
  }

  @ExceptionHandler(VectorException.class)
  ProblemDetail badRequest(VectorException e) {
    return problem(
        HttpStatus.BAD_REQUEST, "validation-failed", "Request validation failed", e.getMessage());
  }

  @ExceptionHandler(QueryValidationException.class)
  ProblemDetail cqlError(QueryValidationException e) {
    ProblemDetail detail =
        problem(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "cql-error",
            "CQL execution failed",
            e.getMessage());
    detail.setProperty("cqlErrorClass", e.getClass().getName());
    return detail;
  }

  private static ProblemDetail problem(
      HttpStatus status, String type, String title, String detail) {
    ProblemDetail problem = ProblemDetail.forStatus(status);
    problem.setType(URI.create(PROBLEM_BASE + type));
    problem.setTitle(title);
    problem.setDetail(detail);
    return problem;
  }

  /** Raised by {@link #require}; rendered as the contract's 501 {@code CapabilityUnsupported}. */
  public static class CapabilityUnsupportedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient Capability capability;

    public CapabilityUnsupportedException(Capability capability, String message) {
      super(message);
      this.capability = capability;
    }

    public Capability capability() {
      return capability;
    }
  }
}
