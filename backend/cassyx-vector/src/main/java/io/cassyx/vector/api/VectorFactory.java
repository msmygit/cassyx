package io.cassyx.vector.api;

import io.cassyx.vector.impl.DefaultAnnQueryBuilder;
import io.cassyx.vector.impl.DefaultSaiIndexManager;

/**
 * Composition entry point of cassyx-vector (plan section 2.1).
 *
 * <pre>{@code
 * String cql = VectorFactory.annQueryBuilder().build(new AnnQuery(
 *     new VectorColumn("demo", "docs", "embedding", 3),
 *     List.of(0.1f, 0.2f, 0.3f), 5,
 *     List.of("similarity_cosine(embedding, [0.1, 0.2, 0.3]) AS score"), Map.of()));
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
}
