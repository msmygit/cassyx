package io.cassyx.vector.api;

import com.datastax.oss.driver.api.core.CqlSession;
import java.util.List;

/**
 * Vector-aware schema access.
 *
 * <p>Requires java-driver-core 4.19.0: 4.16 introduced {@code CqlVector}, and only 4.19.0 emits
 * correct vector DDL from {@code describe} (CASSJAVA-2) and handles vectors in QueryBuilder
 * (JAVA-3118). CASSANDRA-19333 (a {@code VectorCodec} data-corruption bug) is guarded by a
 * round-trip fidelity test at 1536 dimensions.
 */
public interface VectorService {

  List<VectorColumn> vectorColumns(CqlSession session, String keyspace, String table);
}
