package io.cassyx.bulk.api;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cooperative cancellation signal for a running bulk operation (plan section 5.5: every job is
 * cancellable).
 *
 * <p>Deliberately not {@code Future}/{@code Thread.interrupt}: the unload engine polls this between
 * splits and between pages, so a cancelled job stops at a row boundary and the partially written
 * artifact is still a well-formed prefix.
 */
@FunctionalInterface
public interface Cancellation {

  boolean isCancelled();

  /** A signal that never fires. */
  static Cancellation never() {
    return () -> false;
  }

  /** A signal backed by a flag the caller flips. */
  static Cancellation of(AtomicBoolean flag) {
    return flag::get;
  }
}
