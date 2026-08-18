package io.cassyx.bulk.api;

import com.datastax.oss.driver.api.core.CqlSession;
import java.util.Map;

/** Bulk load. Backed by the out-of-process DSBulk runner for large inputs (plan section 5.3). */
public interface LoadEngine {

  UnloadResult load(
      CqlSession session, String keyspace, String table, String source, Map<String, String> options);
}
