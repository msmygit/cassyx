package io.cassyx.vector.api;

import io.cassyx.vector.impl.DefaultAnnQueryBuilder;
import io.cassyx.vector.impl.DefaultSaiIndexManager;
import io.cassyx.vector.impl.DefaultVectorService;

/**
 * Composition entry point of cassyx-vector (plan section 2.1) - the only class that knows about the
 * {@code impl} package.
 *
 * <p>Everything here works with nothing but a {@code CqlSession}: no Spring, no web layer, no UI.
 *
 * <pre>{@code
 * VectorService vectors = VectorFactory.vectorService();
 * VectorColumn embedding = vectors.vectorColumn(session, "demo", "doc_embeddings", "embedding");
 *
 * AnnQueryPreview preview = VectorFactory.annQueryBuilder().preview(
 *     AnnQuery.builder(embedding, queryVector)
 *         .limit(3)
 *         .select(List.of("doc_id", "title"))
 *         .where(AnnPredicate.equalTo("category", "release-notes"))
 *         .score(SimilarityFunction.COSINE)
 *         .build());
 *
 * System.out.println(preview.abbreviatedCql());
 * // SELECT doc_id, title, similarity_cosine(embedding, [... 1536 floats ...]) AS cosine_score
 * //   FROM demo.doc_embeddings WHERE category = 'release-notes'
 * //   ORDER BY embedding ANN OF [... 1536 floats ...] LIMIT 3
 * }</pre>
 */
public final class VectorFactory {

  private VectorFactory() {}

  public static AnnQueryBuilder annQueryBuilder() {
    return new DefaultAnnQueryBuilder();
  }

  public static SaiIndexManager saiIndexManager() {
    return new DefaultSaiIndexManager();
  }

  public static VectorService vectorService() {
    return new DefaultVectorService(saiIndexManager());
  }

  /** For callers that already hold an index manager and want one shared instance. */
  public static VectorService vectorService(SaiIndexManager indexManager) {
    return new DefaultVectorService(indexManager);
  }
}
