package io.cassyx.bulk.api.dsbulk;

/**
 * Callbacks from a running DSBulk process. Implementations must be cheap and non-blocking - they
 * are invoked from the log-tailing thread, and blocking here back-pressures the child process's
 * stdout pipe, which eventually deadlocks it.
 */
public interface DsbulkListener {

  void onProgress(DsbulkProgress progress);

  void onLog(DsbulkLogLine line);

  static DsbulkListener noop() {
    return new DsbulkListener() {
      @Override
      public void onProgress(DsbulkProgress progress) {
        // no-op
      }

      @Override
      public void onLog(DsbulkLogLine line) {
        // no-op
      }
    };
  }
}
