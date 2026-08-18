package io.cassyx.core.impl.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.ExecutionInfo;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.datastax.oss.driver.api.core.cql.Statement;
import io.cassyx.core.api.query.ColumnInfo;
import io.cassyx.core.api.query.PageTokenMismatchException;
import io.cassyx.core.api.query.ResultHandleExpiredException;
import io.cassyx.core.api.query.ResultSetInfo;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The paging-state token stack of plan section 5.1.
 *
 * <p>The behaviour under test is the one that cannot be faked: Cassandra pages forward only, so
 * "previous page" has to replay a token this server kept. These tests assert the tokens actually
 * sent back to the driver, because that is where a wrong implementation silently returns the wrong
 * rows rather than failing.
 */
class ResultSetCacheTest {

  private static final List<ColumnInfo> COLUMNS = List.of(ColumnInfo.simple("id", "int"));

  /** Records every paging state the cache asks the driver to resume from. */
  private final List<String> requestedStates = new ArrayList<>();

  private CqlSession sessionReturning(String... nextStates) {
    CqlSession session = mock(CqlSession.class);
    List<String> states = java.util.Arrays.asList(nextStates);
    when(session.execute(any(Statement.class)))
        .thenAnswer(
            invocation -> {
              Statement<?> statement = invocation.getArgument(0);
              ByteBuffer incoming = statement.getPagingState();
              String label = incoming == null ? "<none>" : text(incoming);
              requestedStates.add(label);
              int index = requestedStates.size() - 1;
              String next = index < states.size() ? states.get(index) : null;
              return resultSet(next);
            });
    return session;
  }

  private static ResultSet resultSet(String nextState) {
    ResultSet rs = mock(ResultSet.class);
    ExecutionInfo info = mock(ExecutionInfo.class);
    when(info.getPagingState()).thenReturn(nextState == null ? null : buffer(nextState));
    when(rs.getExecutionInfo()).thenReturn(info);
    when(rs.getAvailableWithoutFetching()).thenReturn(0);
    return rs;
  }

  private static ByteBuffer buffer(String text) {
    return ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
  }

  private static String text(ByteBuffer buffer) {
    ByteBuffer copy = buffer.duplicate();
    byte[] bytes = new byte[copy.remaining()];
    copy.get(bytes);
    return new String(bytes, StandardCharsets.UTF_8);
  }

  private ResultSetCache.Entry register(ResultSetCache cache, CqlSession session, String firstNextState) {
    return cache.register(
        session,
        SimpleStatement.newInstance("SELECT * FROM demo.users"),
        "SELECT * FROM demo.users",
        COLUMNS,
        "demo",
        "users",
        true,
        500,
        firstNextState == null ? null : buffer(firstNextState),
        3);
  }

  @Test
  @DisplayName("previous-page replays a RETAINED token; it never re-runs the query with an offset")
  void previousPageReplaysTheRetainedToken() {
    ResultSetCache cache = new ResultSetCache();
    CqlSession session = sessionReturning("state-2", "state-3");
    ResultSetCache.Entry entry = register(cache, session, "state-1");

    // page 1 -> 2 uses state-1
    ResultSetCache.Page page2 = cache.fetch(entry.handle(), cache.nextTokenFor(entry), null);
    assertThat(page2.pageNumber()).isEqualTo(2);
    assertThat(requestedStates).containsExactly("state-1");

    // page 2 -> 3 uses state-2, which the driver only revealed on page 2
    ResultSetCache.Page page3 = cache.fetch(entry.handle(), page2.nextPageToken(), null);
    assertThat(page3.pageNumber()).isEqualTo(3);
    assertThat(requestedStates).containsExactly("state-1", "state-2");

    // back to page 2: state-1 again, NOT a fresh scan and NOT an offset
    ResultSetCache.Page back = cache.fetch(entry.handle(), page3.previousPageToken(), null);
    assertThat(back.pageNumber()).isEqualTo(2);
    assertThat(requestedStates).containsExactly("state-1", "state-2", "state-1");

    // and back to page 1, whose paging state is null by definition
    ResultSetCache.Page first = cache.fetch(entry.handle(), back.previousPageToken(), null);
    assertThat(first.pageNumber()).isEqualTo(1);
    assertThat(requestedStates).containsExactly("state-1", "state-2", "state-1", "<none>");
    assertThat(first.previousPageToken()).isNull();
  }

  @Test
  void exhaustedResultSetStopsOfferingANextToken() {
    ResultSetCache cache = new ResultSetCache();
    CqlSession session = sessionReturning((String) null);
    ResultSetCache.Entry entry = register(cache, session, "state-1");

    ResultSetCache.Page last = cache.fetch(entry.handle(), cache.nextTokenFor(entry), null);

    assertThat(last.nextPageToken()).isNull();
    assertThat(entry.hasMorePages()).isFalse();
  }

  @Test
  void aResultSetWithASinglePageHasNoNextToken() {
    ResultSetCache cache = new ResultSetCache();
    ResultSetCache.Entry entry = register(cache, sessionReturning(), null);

    assertThat(cache.nextTokenFor(entry)).isNull();
    assertThat(cache.previousTokenFor(entry)).isNull();
    assertThat(entry.columns()).isEqualTo(COLUMNS);
    assertThat(entry.currentIndex()).isZero();
  }

  @Test
  void fetchSizeCanBeChangedMidStream() {
    ResultSetCache cache = new ResultSetCache();
    CqlSession session = sessionReturning("state-2");
    ResultSetCache.Entry entry = register(cache, session, "state-1");

    cache.fetch(entry.handle(), cache.nextTokenFor(entry), 25);

    assertThat(cache.info(entry.handle()).pagesFetched()).isEqualTo(2);
  }

  @Test
  void reportsStateWithoutTransferringRows() {
    ResultSetCache cache = new ResultSetCache();
    ResultSetCache.Entry entry = register(cache, sessionReturning(), "state-1");

    ResultSetInfo info = cache.info(entry.handle());

    assertThat(info.resultHandle()).isEqualTo(entry.handle());
    assertThat(info.cql()).isEqualTo("SELECT * FROM demo.users");
    assertThat(info.columns()).isEqualTo(COLUMNS);
    assertThat(info.rowsFetched()).isEqualTo(3);
    assertThat(info.hasMorePages()).isTrue();
    assertThat(info.editable()).isTrue();
    assertThat(info.keyspace()).isEqualTo("demo");
    assertThat(info.table()).isEqualTo("users");
    assertThat(info.expiresAt()).isAfter(Instant.now());
  }

  @Test
  void recordsRowsDrainedFromLaterPages() {
    ResultSetCache cache = new ResultSetCache();
    ResultSetCache.Entry entry = register(cache, sessionReturning(), null);

    cache.recordRows(entry, 7);

    assertThat(cache.info(entry.handle()).rowsFetched()).isEqualTo(10);
  }

  @Test
  void unknownOrReleasedHandlesExpire() {
    ResultSetCache cache = new ResultSetCache();
    ResultSetCache.Entry entry = register(cache, sessionReturning(), null);

    assertThat(cache.close(entry.handle())).isTrue();
    assertThat(cache.close(entry.handle())).isFalse();
    assertThatThrownBy(() -> cache.info(entry.handle()))
        .isInstanceOf(ResultHandleExpiredException.class);
    assertThatThrownBy(() -> cache.get(null)).isInstanceOf(ResultHandleExpiredException.class);
  }

  @Test
  @DisplayName("A handle idle past its TTL is gone, and says so")
  void idleHandlesExpireAfterTheTtl() {
    MutableClock clock = new MutableClock(Instant.parse("2026-08-17T10:00:00Z"));
    ResultSetCache cache = new ResultSetCache(Duration.ofMinutes(10), clock);
    ResultSetCache.Entry entry = register(cache, sessionReturning(), null);

    clock.advance(Duration.ofMinutes(11));

    assertThatThrownBy(() -> cache.info(entry.handle()))
        .isInstanceOf(ResultHandleExpiredException.class)
        .hasMessageContaining("expired after 10 minutes idle");
    assertThat(cache.size()).isZero();
  }

  @Test
  void sweepDropsOnlyExpiredHandles() {
    MutableClock clock = new MutableClock(Instant.parse("2026-08-17T10:00:00Z"));
    ResultSetCache cache = new ResultSetCache(Duration.ofMinutes(10), clock);
    register(cache, sessionReturning(), null);
    clock.advance(Duration.ofMinutes(11));
    register(cache, sessionReturning(), null);

    assertThat(cache.evictExpired()).isEqualTo(1);
    assertThat(cache.size()).isEqualTo(1);
  }

  @Test
  @DisplayName("A token from another result set is rejected rather than silently paging the wrong one")
  void tokensAreBoundToTheirHandle() {
    ResultSetCache cache = new ResultSetCache();
    ResultSetCache.Entry first = register(cache, sessionReturning("s"), "state-1");
    ResultSetCache.Entry second = register(cache, sessionReturning("s"), "state-1");

    assertThatThrownBy(() -> cache.fetch(second.handle(), cache.nextTokenFor(first), null))
        .isInstanceOf(PageTokenMismatchException.class)
        .hasMessageContaining("different result set");
  }

  @Test
  void malformedOrUnreachableTokensAreRejected() {
    ResultSetCache cache = new ResultSetCache();
    ResultSetCache.Entry entry = register(cache, sessionReturning(), null);
    String handle = entry.handle();

    assertThatThrownBy(() -> cache.fetch(handle, null, null))
        .isInstanceOf(PageTokenMismatchException.class);
    assertThatThrownBy(() -> cache.fetch(handle, "!!!not base64!!!", null))
        .isInstanceOf(PageTokenMismatchException.class);
    assertThatThrownBy(
            () ->
                cache.fetch(
                    handle,
                    java.util.Base64.getUrlEncoder().withoutPadding()
                        .encodeToString("no-separator".getBytes(StandardCharsets.UTF_8)),
                    null))
        .isInstanceOf(PageTokenMismatchException.class);
    assertThatThrownBy(
            () ->
                cache.fetch(
                    handle,
                    java.util.Base64.getUrlEncoder().withoutPadding()
                        .encodeToString((handle + ":x").getBytes(StandardCharsets.UTF_8)),
                    null))
        .isInstanceOf(PageTokenMismatchException.class);
    // A page we have never reached has no retained token, so it cannot be jumped to.
    assertThatThrownBy(() -> cache.fetch(handle, cache.token(handle, 42), null))
        .isInstanceOf(PageTokenMismatchException.class)
        .hasMessageContaining("has not been reached");
  }

  @Test
  void handlesAreUniqueAndOpaque() {
    ResultSetCache cache = new ResultSetCache();

    String a = register(cache, sessionReturning(), null).handle();
    String b = register(cache, sessionReturning(), null).handle();

    assertThat(a).startsWith("rs_").isNotEqualTo(b);
  }

  /** Test clock the cache's TTL logic can be driven with. */
  private static final class MutableClock extends Clock {

    private Instant now;

    private MutableClock(Instant now) {
      this.now = now;
    }

    void advance(Duration duration) {
      now = now.plus(duration);
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
