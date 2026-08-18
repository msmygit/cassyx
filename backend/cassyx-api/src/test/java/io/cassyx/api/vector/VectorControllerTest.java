package io.cassyx.api.vector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.datastax.oss.driver.api.core.CqlSession;
import io.cassyx.core.api.Capability;
import io.cassyx.vector.api.SaiIndexDescriptor;
import io.cassyx.vector.api.SaiIndexState;
import io.cassyx.vector.api.SaiIndexStatus;
import io.cassyx.vector.api.SimilarityFunction;
import io.cassyx.vector.api.SimilarityScores;
import io.cassyx.vector.api.VectorCapabilities;
import io.cassyx.vector.api.VectorColumn;
import io.cassyx.vector.api.VectorException;
import io.cassyx.vector.api.VectorFactory;
import io.cassyx.vector.api.VectorService;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * The adapter's own decisions: capability gating, DTO mapping and the RFC 9457 error shapes. The
 * CQL generation underneath is cassyx-vector's and is tested there against a real cluster.
 */
class VectorControllerTest {

  private static final SaiIndexDescriptor INDEX =
      new SaiIndexDescriptor(
          "demo",
          "doc_embeddings",
          "doc_embeddings_ann",
          "embedding",
          true,
          SimilarityFunction.COSINE,
          null,
          Map.of("similarity_function", "cosine"),
          "org.apache.cassandra.index.sai.StorageAttachedIndex");

  private static final VectorColumn COLUMN =
      new VectorColumn("demo", "doc_embeddings", "embedding", 1536).withIndex(INDEX);

  /** A {@link VectorService} whose capability answer is the only thing under test. */
  private static VectorService serviceReporting(VectorCapabilities capabilities) {
    return new StubVectorService(capabilities);
  }

  private VectorController controller(VectorCapabilities capabilities) {
    return new VectorController(
        connectionId -> null,
        serviceReporting(capabilities),
        VectorFactory.saiIndexManager(),
        VectorFactory.annQueryBuilder());
  }

  /* ------------------------------------------------------------- capability gating */

  @Test
  @DisplayName("Keyspaces and Scylla get a 501 with the capability named, not a broken feature")
  void unsupportedCapabilityBecomesA501Problem() {
    VectorController controller = controller(new VectorCapabilities(false, false, "SCYLLA", "6.0"));

    assertThatThrownBy(() -> controller.listVectorColumns("c1", "demo", "doc_embeddings"))
        .isInstanceOf(VectorController.CapabilityUnsupportedException.class)
        .hasMessageContaining("Cassandra 5.x or Astra")
        .hasMessageContaining("SCYLLA 6.0");

    assertThatThrownBy(() -> controller.listSaiIndexes("c1", "demo", "doc_embeddings"))
        .isInstanceOf(VectorController.CapabilityUnsupportedException.class)
        .hasMessageContaining("DSE 6.8+");
  }

  @Test
  @DisplayName("DSE 6.8 keeps SAI but loses vector/ANN")
  void dseGetsSaiButNotVectorAnn() {
    VectorController controller = controller(new VectorCapabilities(false, true, "DSE", "6.8.30"));

    assertThatThrownBy(() -> controller.listVectorColumns("c1", "demo", "doc_embeddings"))
        .isInstanceOf(VectorController.CapabilityUnsupportedException.class);

    // SAI is allowed, so the gate lets it through and the null session fails later instead.
    assertThatThrownBy(() -> controller.listSaiIndexes("c1", "demo", "doc_embeddings"))
        .isNotInstanceOf(VectorController.CapabilityUnsupportedException.class);
  }

  @Test
  void the501ProblemCarriesTheContractsCapabilityName() {
    VectorController controller = controller(VectorCapabilities.permissive());

    ProblemDetail sai =
        controller.unsupported(
            new VectorController.CapabilityUnsupportedException(Capability.SAI, "no SAI here"));
    assertThat(sai.getStatus()).isEqualTo(HttpStatus.NOT_IMPLEMENTED.value());
    assertThat(sai.getProperties()).containsEntry("capability", "sai");
    assertThat(sai.getType().toString()).isEqualTo("https://cassyx.dev/problems/capability-unsupported");

    ProblemDetail vector =
        controller.unsupported(
            new VectorController.CapabilityUnsupportedException(
                Capability.VECTOR_ANN, "no vectors here"));
    assertThat(vector.getProperties()).containsEntry("capability", "vector");
  }

  /* ------------------------------------------------------------------ error shapes */

  @Test
  void errorsAreRfc9457ProblemDocuments() {
    VectorController controller = controller(VectorCapabilities.permissive());

    ProblemDetail notConnected =
        controller.notConnected(new VectorSessionResolver.NoLiveSessionException("prod-eu"));
    assertThat(notConnected.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
    assertThat(notConnected.getDetail()).contains("prod-eu").contains("Connect it first");

    ProblemDetail bad = controller.badRequest(new VectorException("dimensions do not match"));
    assertThat(bad.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    assertThat(bad.getTitle()).isEqualTo("Request validation failed");
  }

  /* -------------------------------------------------------------------- DTO shapes */

  @Test
  void mapsAVectorColumnOntoTheContractShape() {
    VectorDtos.VectorColumnResponse response = VectorDtos.VectorColumnResponse.from(COLUMN);

    assertThat(response.name()).isEqualTo("embedding");
    assertThat(response.dimensions()).isEqualTo(1536);
    assertThat(response.elementType()).isEqualTo("float");
    assertThat(response.cqlType()).isEqualTo("vector<float, 1536>");
    assertThat(response.similarityFunction()).isEqualTo("cosine");
    assertThat(response.identity().kind()).isEqualTo("COLUMN");
    assertThat(response.identity().qualifiedName()).isEqualTo("demo.doc_embeddings.embedding");
    assertThat(response.index().vectorIndex()).isTrue();
    assertThat(response.index().identity().kind()).isEqualTo("INDEX");
    assertThat(response.index().className()).endsWith("StorageAttachedIndex");
  }

  @Test
  void anUnindexedColumnReportsNoIndex() {
    VectorDtos.VectorColumnResponse response =
        VectorDtos.VectorColumnResponse.from(new VectorColumn("demo", "docs", "v", 3));

    assertThat(response.index()).isNull();
    assertThat(response.similarityFunction()).isNull();
  }

  @Test
  void mapsIndexStatusIncludingPerNodeState() {
    SaiIndexStatus status =
        new SaiIndexStatus(
            "demo",
            "doc_embeddings",
            "doc_embeddings_ann",
            SaiIndexState.BUILDING,
            false,
            50.0d,
            List.of(
                new io.cassyx.vector.api.SaiIndexNodeStatus("10.0.0.1:9042", SaiIndexState.QUERYABLE),
                new io.cassyx.vector.api.SaiIndexNodeStatus("10.0.0.2:9042", SaiIndexState.BUILDING)),
            INDEX);

    VectorDtos.SaiIndexStatusResponse response = VectorDtos.SaiIndexStatusResponse.from(status);

    assertThat(response.state()).isEqualTo("BUILDING");
    assertThat(response.queryable()).isFalse();
    assertThat(response.buildProgressPercent()).isEqualTo(50.0d);
    assertThat(response.perNode())
        .extracting(VectorDtos.SaiIndexNodeStatusResponse::state)
        .containsExactly("QUERYABLE", "BUILDING");
    assertThat(response.definition()).isNotNull();

    assertThat(
            VectorDtos.SaiIndexStatusResponse.from(
                    SaiIndexStatus.unknown("demo", "doc_embeddings", "gone"))
                .definition())
        .isNull();
  }

  @Test
  void mapsSimilarityScoresByTheirCqlNames() {
    VectorDtos.SimilarityResult result =
        VectorDtos.SimilarityResult.from(
            new SimilarityScores(
                3,
                Map.of(SimilarityFunction.COSINE, 0.92d, SimilarityFunction.EUCLIDEAN, 0.39d),
                1.0d,
                1.0d),
            null);

    assertThat(result.dimensions()).isEqualTo(3);
    assertThat(result.scores()).containsOnlyKeys("cosine", "euclidean");
    assertThat(result.warnings()).isEmpty();
  }

  @Test
  void parsesSimilarityFunctionNamesFromTheWire() {
    assertThat(VectorDtos.similarityFunction("dot_product"))
        .isEqualTo(SimilarityFunction.DOT_PRODUCT);
    assertThat(VectorDtos.similarityFunction(null)).isNull();
  }

  /* ------------------------------------------------------------------ session seam */

  @Test
  @DisplayName("Without a session registry the app still boots and says so legibly")
  void fallbackResolverFailsWithNotConnectedRatherThanAtStartup() {
    VectorSessionResolver resolver = new VectorConfiguration().vectorSessionResolver(new NoProvider());

    assertThatThrownBy(() -> resolver.resolve("c1"))
        .isInstanceOf(VectorSessionResolver.NoLiveSessionException.class)
        .hasMessageContaining("No session registry");
  }

  @Test
  void adaptsAPlainFunctionFromWorkstreamA() {
    assertThatThrownBy(() -> VectorSessionResolver.of(id -> null).resolve("c1"))
        .isInstanceOf(VectorSessionResolver.NoLiveSessionException.class);
  }

  @Test
  void adoptsTheSessionLookupWorkstreamAPublishesIfThereIsOne() {
    VectorSessionResolver resolver =
        new VectorConfiguration().vectorSessionResolver(new NoProvider(id -> null));

    // Present but returning no session: still NotConnected, not a NullPointerException downstream.
    assertThatThrownBy(() -> resolver.resolve("c1"))
        .isInstanceOf(VectorSessionResolver.NoLiveSessionException.class)
        .hasMessageContaining("Connect it first");
  }

  /** An {@link ObjectProvider} that yields the supplied lookup, or nothing. */
  private record NoProvider(Function<String, CqlSession> lookup)
      implements ObjectProvider<Function<String, CqlSession>> {

    NoProvider() {
      this(null);
    }

    @Override
    public Function<String, CqlSession> getObject(Object... args) {
      return getObject();
    }

    @Override
    public Function<String, CqlSession> getObject() {
      if (lookup == null) {
        throw new NoSuchBeanDefinitionException(Function.class);
      }
      return lookup;
    }

    @Override
    public Function<String, CqlSession> getIfAvailable() {
      return lookup;
    }

    @Override
    public Function<String, CqlSession> getIfUnique() {
      return lookup;
    }
  }

  /** Minimal stand-in: only {@link #capabilities} is exercised by these tests. */
  private record StubVectorService(VectorCapabilities capabilities) implements VectorService {

    @Override
    public List<VectorColumn> vectorColumns(CqlSession session, String keyspace, String table) {
      return List.of(COLUMN);
    }

    @Override
    public VectorColumn vectorColumn(
        CqlSession session, String keyspace, String table, String column) {
      return COLUMN;
    }

    @Override
    public List<String> addColumnCql(
        String keyspace, String table, io.cassyx.vector.api.VectorColumnDefinition definition) {
      return List.of();
    }

    @Override
    public List<Float> readVector(
        CqlSession session,
        String keyspace,
        String table,
        String column,
        Map<String, Object> primaryKey) {
      return List.of();
    }

    @Override
    public SimilarityScores compare(
        List<Float> left,
        List<Float> right,
        java.util.Collection<SimilarityFunction> functions) {
      return new SimilarityScores(0, Map.of(), 0, 0);
    }

    @Override
    public double magnitude(List<Float> vector) {
      return 0;
    }

    @Override
    public VectorCapabilities capabilities(CqlSession session) {
      return capabilities;
    }
  }
}
