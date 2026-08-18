package io.cassyx.core.api;

import com.datastax.oss.driver.api.core.CqlSession;

/**
 * Interactive query execution with server-side paging (plan section 5.1). The paging state is an
 * opaque token handed back to the client, which fixes the {@code LIMIT 100} dead end of the prior
 * art.
 */
public interface QueryExecutor {

  QueryResultPage execute(CqlSession session, QueryRequest request);
}
