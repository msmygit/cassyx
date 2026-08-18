package io.cassyx.core.impl;

import com.datastax.oss.driver.api.core.ConsistencyLevel;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.DefaultConsistencyLevel;
import com.datastax.oss.driver.api.core.cql.ColumnDefinition;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.datastax.oss.driver.api.core.cql.SimpleStatementBuilder;
import io.cassyx.core.api.CassyxCoreException;
import io.cassyx.core.api.QueryExecutor;
import io.cassyx.core.api.QueryRequest;
import io.cassyx.core.api.QueryResultPage;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Server-side paging via the driver's {@code PagingState}. Only the rows already available on the
 * current page are drained, so iteration never silently pulls the next page.
 */
public final class PagingQueryExecutor implements QueryExecutor {

  @Override
  public QueryResultPage execute(CqlSession session, QueryRequest request) {
    SimpleStatementBuilder builder =
        SimpleStatement.builder(request.cql()).setPageSize(request.fetchSize());
    if (request.pagingState() != null && !request.pagingState().isBlank()) {
      builder.setPagingState(decodePagingState(request.pagingState()));
    }
    if (request.consistency() != null && !request.consistency().isBlank()) {
      builder.setConsistencyLevel(parseConsistency(request.consistency()));
    }
    if (request.tracing()) {
      builder.setTracing(true);
    }
    if (request.timeout() != null) {
      builder.setTimeout(request.timeout());
    }

    ResultSet rs = session.execute(builder.build());
    List<String> columns = new ArrayList<>();
    for (ColumnDefinition def : rs.getColumnDefinitions()) {
      columns.add(def.getName().asInternal());
    }

    List<Map<String, Object>> rows = new ArrayList<>();
    int available = rs.getAvailableWithoutFetching();
    for (int i = 0; i < available; i++) {
      Row row = rs.one();
      if (row == null) {
        break;
      }
      Map<String, Object> values = new LinkedHashMap<>();
      for (String column : columns) {
        values.put(column, row.getObject(column));
      }
      rows.add(values);
    }

    Boolean applied = columns.contains("[applied]") ? rs.wasApplied() : null;
    String tracingId =
        rs.getExecutionInfo().getTracingId() == null
            ? null
            : rs.getExecutionInfo().getTracingId().toString();
    return new QueryResultPage(
        columns, rows, encodePagingState(rs.getExecutionInfo().getPagingState()), applied, tracingId);
  }

  /** Visible for testing. */
  public static String encodePagingState(ByteBuffer state) {
    if (state == null) {
      return null;
    }
    ByteBuffer copy = state.duplicate();
    byte[] bytes = new byte[copy.remaining()];
    copy.get(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /** Visible for testing. */
  public static ByteBuffer decodePagingState(String encoded) {
    try {
      return ByteBuffer.wrap(Base64.getUrlDecoder().decode(encoded));
    } catch (IllegalArgumentException e) {
      throw new CassyxCoreException("Invalid paging state token", e);
    }
  }

  /** Visible for testing. */
  public static ConsistencyLevel parseConsistency(String name) {
    try {
      return DefaultConsistencyLevel.valueOf(name.trim().toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new CassyxCoreException("Unknown consistency level '" + name + "'", e);
    }
  }
}
