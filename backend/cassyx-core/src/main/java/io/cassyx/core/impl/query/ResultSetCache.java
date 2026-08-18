package io.cassyx.core.impl.query;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Statement;
import io.cassyx.core.api.query.ColumnInfo;
import io.cassyx.core.api.query.PageTokenMismatchException;
import io.cassyx.core.api.query.ResultHandleExpiredException;
import io.cassyx.core.api.query.ResultSetInfo;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Server-side result-set cache: the driver's {@code PagingState} plus a retained stack of the tokens
 * already visited.
 *
 * <p><b>Why the stack exists.</b> A Cassandra paging state is forward-only - it says "resume after
 * this row", and there is no inverse. CQL has no {@code OFFSET} either, so you cannot fake "previous
 * page" by re-running the query and skipping rows: on a table taking concurrent writes that skips or
 * repeats rows, and on a large table it re-reads everything from the start. The only correct answer
 * is to remember the tokens we handed out on the way forward and replay one. That is
 * {@link #pageStates}.
 *
 * <p>Handles expire after an idle TTL (default 10 minutes) so a browser tab left open overnight does
 * not pin paging state for every query it ever ran.
 */
public final class ResultSetCache {

  public static final Duration DEFAULT_TTL = Duration.ofMinutes(10);

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final AtomicLong SEQUENCE = new AtomicLong();

  private final Map<String, Entry> entries = new ConcurrentHashMap<>();
  private final Duration ttl;
  private final Clock clock;

  public ResultSetCache() {
    this(DEFAULT_TTL, Clock.systemUTC());
  }

  public ResultSetCache(Duration ttl, Clock clock) {
    this.ttl = ttl == null ? DEFAULT_TTL : ttl;
    this.clock = clock == null ? Clock.systemUTC() : clock;
  }

  /** One cached result set. Mutated only under its own monitor. */
  public static final class Entry {

    private final String handle;
    private final CqlSession session;
    private final Statement<?> base;
    private final String cql;
    private final List<ColumnInfo> columns;
    private final String keyspace;
    private final String table;
    private final boolean editable;

    /** {@code pageStates.get(i)} is the paging state that yields page {@code i} (0-based). */
    private final List<ByteBuffer> pageStates = new ArrayList<>();

    private int currentIndex;
    private int pagesFetched;
    private long rowsFetched;
    private int fetchSize;
    private boolean hasMorePages;
    private volatile Instant lastAccess;

    private Entry(
        String handle,
        CqlSession session,
        Statement<?> base,
        String cql,
        List<ColumnInfo> columns,
        String keyspace,
        String table,
        boolean editable,
        int fetchSize,
        Instant now) {
      this.handle = handle;
      this.session = session;
      this.base = base;
      this.cql = cql;
      this.columns = List.copyOf(columns);
      this.keyspace = keyspace;
      this.table = table;
      this.editable = editable;
      this.fetchSize = fetchSize;
      this.lastAccess = now;
      this.pageStates.add(null); // page 0 needs no paging state
    }

    public String handle() {
      return handle;
    }

    public List<ColumnInfo> columns() {
      return columns;
    }

    public int currentIndex() {
      return currentIndex;
    }

    public boolean hasMorePages() {
      return hasMorePages;
    }
  }

  /** Result of fetching one page: the driver result set plus the tokens to give the client. */
  public record Page(
      Entry entry, ResultSet resultSet, int pageNumber, String nextPageToken, String previousPageToken) {}

  /**
   * Registers a freshly executed result set and records the paging state for its second page.
   *
   * @param rowsInFirstPage rows already drained from {@code resultSet} by the caller
   */
  public Entry register(
      CqlSession session,
      Statement<?> base,
      String cql,
      List<ColumnInfo> columns,
      String keyspace,
      String table,
      boolean editable,
      int fetchSize,
      ByteBuffer nextPagingState,
      long rowsInFirstPage) {

    String handle = newHandle();
    Entry entry =
        new Entry(
            handle, session, base, cql, columns, keyspace, table, editable, fetchSize, clock.instant());
    entry.pagesFetched = 1;
    entry.rowsFetched = rowsInFirstPage;
    entry.currentIndex = 0;
    if (nextPagingState != null) {
      entry.pageStates.add(nextPagingState.duplicate());
      entry.hasMorePages = true;
    }
    entries.put(handle, entry);
    return entry;
  }

  /** Opaque token naming a (handle, page index) pair. Clients must never decode one. */
  public String token(String handle, int pageIndex) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString((handle + ":" + pageIndex).getBytes(StandardCharsets.UTF_8));
  }

  public String nextTokenFor(Entry entry) {
    return entry.hasMorePages ? token(entry.handle, entry.currentIndex + 1) : null;
  }

  public String previousTokenFor(Entry entry) {
    return entry.currentIndex > 0 ? token(entry.handle, entry.currentIndex - 1) : null;
  }

  /**
   * Fetches the page a token names.
   *
   * @throws ResultHandleExpiredException if the handle is unknown or idle-expired
   * @throws PageTokenMismatchException if the token belongs to a different handle
   */
  public Page fetch(String handle, String pageToken, Integer fetchSize) {
    Entry entry = require(handle);
    int index = decodeIndex(handle, pageToken);
    synchronized (entry) {
      if (fetchSize != null && fetchSize > 0) {
        entry.fetchSize = fetchSize;
      }
      if (index < 0 || index >= entry.pageStates.size()) {
        throw new PageTokenMismatchException(
            "Page " + (index + 1) + " has not been reached on this result set yet.");
      }
      ByteBuffer state = entry.pageStates.get(index);
      Statement<?> statement = entry.base.setPageSize(entry.fetchSize);
      statement = statement.setPagingState(state == null ? null : state.duplicate());

      ResultSet rs = entry.session.execute(statement);
      ByteBuffer next = rs.getExecutionInfo().getPagingState();
      entry.currentIndex = index;
      entry.pagesFetched++;
      if (next != null) {
        if (entry.pageStates.size() == index + 1) {
          entry.pageStates.add(next.duplicate());
        } else {
          entry.pageStates.set(index + 1, next.duplicate());
        }
        entry.hasMorePages = true;
      } else {
        entry.hasMorePages = false;
      }
      entry.lastAccess = clock.instant();
      return new Page(entry, rs, index + 1, nextTokenFor(entry), previousTokenFor(entry));
    }
  }

  /** Records rows drained from a page fetched via {@link #fetch}. */
  public void recordRows(Entry entry, long rows) {
    synchronized (entry) {
      entry.rowsFetched += rows;
      entry.lastAccess = clock.instant();
    }
  }

  public ResultSetInfo info(String handle) {
    Entry entry = require(handle);
    synchronized (entry) {
      return new ResultSetInfo(
          entry.handle,
          entry.cql,
          entry.columns,
          entry.pagesFetched,
          entry.rowsFetched,
          entry.hasMorePages,
          entry.editable,
          entry.keyspace,
          entry.table,
          entry.lastAccess.plus(ttl));
    }
  }

  public Entry get(String handle) {
    return require(handle);
  }

  /** @return true if the handle existed */
  public boolean close(String handle) {
    return entries.remove(handle) != null;
  }

  /** Drops every idle-expired handle. Cheap enough to run on a timer. */
  public int evictExpired() {
    Instant cutoff = clock.instant().minus(ttl);
    int before = entries.size();
    entries.values().removeIf(entry -> entry.lastAccess.isBefore(cutoff));
    return before - entries.size();
  }

  public int size() {
    return entries.size();
  }

  private Entry require(String handle) {
    Entry entry = handle == null ? null : entries.get(handle);
    if (entry == null) {
      throw new ResultHandleExpiredException(
          handle, "Result handle " + handle + " is unknown or has expired. Re-run the query.");
    }
    if (entry.lastAccess.isBefore(clock.instant().minus(ttl))) {
      entries.remove(handle);
      throw new ResultHandleExpiredException(
          handle,
          "Result handle " + handle + " expired after " + ttl.toMinutes() + " minutes idle.");
    }
    return entry;
  }

  private int decodeIndex(String handle, String pageToken) {
    if (pageToken == null || pageToken.isBlank()) {
      throw new PageTokenMismatchException("A page token is required.");
    }
    String decoded;
    try {
      decoded = new String(Base64.getUrlDecoder().decode(pageToken), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      throw new PageTokenMismatchException("Malformed page token.");
    }
    int separator = decoded.lastIndexOf(':');
    if (separator < 0) {
      throw new PageTokenMismatchException("Malformed page token.");
    }
    if (!decoded.substring(0, separator).equals(handle)) {
      throw new PageTokenMismatchException(
          "This page token belongs to a different result set. Re-run the query.");
    }
    try {
      return Integer.parseInt(decoded.substring(separator + 1));
    } catch (NumberFormatException e) {
      throw new PageTokenMismatchException("Malformed page token.");
    }
  }

  private static String newHandle() {
    byte[] entropy = new byte[10];
    RANDOM.nextBytes(entropy);
    return "rs_"
        + Long.toHexString(SEQUENCE.incrementAndGet())
        + Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
  }
}
