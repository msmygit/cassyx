package io.cassyx.bulk.api.dsbulk;

import java.nio.file.Path;
import java.util.Map;

/**
 * Runs a {@link DsbulkPlan} as a separate JVM process.
 *
 * <p>Out-of-process is mandatory, not an optimisation (plan section 5.3): DSBulk's
 * {@code application.conf} collides with Spring's on a shared classpath. It also means cancellation
 * is a real kill rather than a cooperative interrupt, memory is capped per job, and DSBulk's
 * {@code System.exit()} cannot take the API down.
 */
public interface DsbulkRunner {

  /**
   * Writes the plan's HOCON into {@code jobDirectory}, starts the child process and blocks until it
   * exits or the calling thread is interrupted.
   *
   * <p>{@code plan} is the MASKED artifact - it is what the API returns and what cassyx persists,
   * so its secret values read {@code ***}. The real credentials are supplied separately in
   * {@code secrets} and go straight into the file on disk. Keeping them out of the plan makes
   * "a credential never reaches the browser or the job table" a property of the types rather than
   * of everyone's discipline.
   *
   * @param jobDirectory per-job temp dir; receives {@code dsbulk.conf} and the DSBulk log directory
   * @param secrets real values for the plan's masked settings, keyed by setting path
   * @param listener progress and log callbacks; must not block
   */
  DsbulkResult run(DsbulkPlan plan, Path jobDirectory, Map<String, String> secrets, DsbulkListener listener);

  /**
   * Requests cancellation of the job running in {@code jobDirectory}: SIGTERM first so DSBulk can
   * flush its checkpoint and error reports, then SIGKILL after a grace period.
   *
   * @return {@code true} when a process was found and signalled
   */
  boolean cancel(Path jobDirectory);
}
