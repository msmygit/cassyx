package io.cassyx.core.api.query;

import com.datastax.oss.driver.api.core.CqlSession;
import java.util.Optional;

/**
 * Interactive query execution with server-side paging (plan section 5.1).
 *
 * <p>Design points that are the whole reason this exists:
 *
 * <ul>
 *   <li><b>Server-side paging via the driver's {@code PagingState}</b>, fetch size 500 by default.
 *       The prior-art prototype hardcoded {@code LIMIT 100} and had no cursor handling at all.
 *   <li><b>{@code previous-page} is a retained token stack, not an offset re-run.</b> Cassandra's
 *       paging state is forward-only and CQL has no {@code OFFSET}, so the only correct way to go
 *       back is to keep the tokens already seen. That is what {@link #previousPage} replays.
 *   <li><b>Result handles expire.</b> Ten minutes idle by default, then {@code 404}.
 *   <li><b>Every execution is cancellable.</b> Work runs on a virtual thread keyed by
 *       {@code queryId}; {@link #cancel} interrupts it and cancels the driver's
 *       {@code CompletionStage}.
 * </ul>
 */
public interface QueryService {

  /** Executes a single statement and returns its first page. */
  ResultPage execute(CqlSession session, QuerySpec spec);

  /** Fetches the page identified by an opaque token produced by a previous {@link ResultPage}. */
  ResultPage nextPage(String resultHandle, String pageToken, Integer fetchSize);

  /** Replays a retained token to re-fetch an earlier page. */
  ResultPage previousPage(String resultHandle, String pageToken, Integer fetchSize);

  /** Metadata about a cached result set without transferring rows. */
  ResultSetInfo resultSetInfo(String resultHandle);

  /** Releases a cached result set and its retained paging tokens early. */
  void closeResultSet(String resultHandle);

  QueryCancellation cancel(String queryId);

  /** The {@code system_traces} timeline, when the query ran with tracing enabled. */
  Optional<QueryTrace> trace(String queryId);

  /** Assembles, analyses and (unless {@link BatchSpec#previewOnly()}) executes a batch. */
  BatchOutcome executeBatch(CqlSession session, BatchSpec spec);

  /**
   * Drops every idle-expired result handle. Cheap; intended to be run on a timer so a browser tab
   * left open overnight does not pin paging state for every query it ever ran.
   *
   * @return how many handles were released
   */
  int sweepExpiredResultSets();
}
