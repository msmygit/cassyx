package io.cassyx.bulk.api;

/** Callback for {@link JobProgress}. Implementations must be cheap and non-blocking. */
@FunctionalInterface
public interface ProgressListener {

  void onProgress(JobProgress progress);

  static ProgressListener noop() {
    return progress -> {};
  }
}
