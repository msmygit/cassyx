package io.cassyx.api.query;

import com.datastax.oss.driver.api.core.DriverException;
import java.util.function.Supplier;

/** Wraps driver failures so the RFC 9457 problem document can name the statement that failed. */
public final class CqlExecution {

  private CqlExecution() {}

  public static <T> T run(String cql, Supplier<T> action) {
    try {
      return action.get();
    } catch (DriverException e) {
      throw new CqlExecutionException(cql, e);
    }
  }
}
