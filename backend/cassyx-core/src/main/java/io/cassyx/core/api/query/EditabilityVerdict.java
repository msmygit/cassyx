package io.cassyx.core.api.query;

import java.util.List;

/**
 * Whether a result set can be edited in place and, when it cannot, <b>why</b>.
 *
 * <p>Plan section 7: never fail silently. {@link #reason()} is shown to the user verbatim, because
 * "this grid is read-only" without an explanation is the single most annoying behaviour of every
 * other Cassandra GUI.
 */
public record EditabilityVerdict(
    boolean editable,
    List<String> requiredKeyColumns,
    List<String> missingKeyColumns,
    String reason,
    String suggestedCql) {

  public EditabilityVerdict {
    requiredKeyColumns = requiredKeyColumns == null ? List.of() : List.copyOf(requiredKeyColumns);
    missingKeyColumns = missingKeyColumns == null ? List.of() : List.copyOf(missingKeyColumns);
  }
}
