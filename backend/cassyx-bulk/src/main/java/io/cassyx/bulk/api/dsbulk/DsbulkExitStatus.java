package io.cassyx.bulk.api.dsbulk;

/**
 * DSBulk's process exit statuses.
 *
 * <p>Mirrors {@code com.datastax.oss.dsbulk.runner.ExitStatus} in the upstream runner. We do not
 * link against it - {@code dsbulk-runner} is deliberately NOT a compile dependency of any cassyx
 * module (plan section 5.3) - so the mapping is duplicated here and asserted by test.
 *
 * <p>The distinction that matters operationally is {@link #COMPLETED_WITH_ERRORS}: the job ran to
 * the end and wrote its output, but some records were rejected into {@code *-errors.log}. Treating
 * that as a plain failure hides a mostly-successful load; treating it as success hides data loss.
 * cassyx reports it as SUCCEEDED with a non-zero failure count and the error report as an artifact.
 */
public enum DsbulkExitStatus {
  OK(0),
  COMPLETED_WITH_ERRORS(1),
  ABORTED_TOO_MANY_ERRORS(2),
  ABORTED_FATAL_ERROR(3),
  INTERRUPTED(4),
  CRASHED(5);

  private final int code;

  DsbulkExitStatus(int code) {
    this.code = code;
  }

  public int code() {
    return code;
  }

  /** True when the workflow ran to completion, with or without rejected records. */
  public boolean isSuccess() {
    return this == OK || this == COMPLETED_WITH_ERRORS;
  }

  /** True when cassyx should surface the job as CANCELLED rather than FAILED. */
  public boolean isInterrupted() {
    return this == INTERRUPTED;
  }

  /** Unknown exit codes map to {@link #CRASHED}: an unrecognised status is not a success. */
  public static DsbulkExitStatus of(int exitCode) {
    for (DsbulkExitStatus status : values()) {
      if (status.code == exitCode) {
        return status;
      }
    }
    return CRASHED;
  }
}
