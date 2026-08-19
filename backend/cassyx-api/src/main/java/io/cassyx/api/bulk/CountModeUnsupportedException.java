package io.cassyx.api.bulk;

import java.util.List;

/**
 * A requested statistics mode cannot be computed for this table on this cluster.
 *
 * <p>Mapped to 422 rather than 400: the request is well formed and would be valid against a
 * different table or a different target, so it is the state of the world that rejects it, not the
 * syntax. And rather than 501, because the endpoint exists and works - it is this particular
 * combination that does not.
 *
 * <p>The alternative was to silently drop the mode, which is how the Statistics tab used to fail:
 * DSBulk threw at workflow init, the job went FAILED with a stack trace in the log, and the user
 * saw a job that broke for no stated reason.
 */
public class CountModeUnsupportedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final List<String> modes;

  public CountModeUnsupportedException(List<String> modes, String message) {
    super(message);
    this.modes = modes == null ? List.of() : List.copyOf(modes);
  }

  /** The modes that were refused, so the UI can drop exactly those and offer to retry. */
  public List<String> modes() {
    return modes;
  }
}
