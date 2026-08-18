package io.cassyx.bulk.api;

/** Progress tick pushed to the job substrate and on to SSE subscribers (plan section 5.5). */
public record JobProgress(
    long rowsProcessed, int splitsCompleted, int splitsTotal, String message) {

  public double fraction() {
    return splitsTotal <= 0 ? 0 : Math.min(1.0, (double) splitsCompleted / splitsTotal);
  }
}
