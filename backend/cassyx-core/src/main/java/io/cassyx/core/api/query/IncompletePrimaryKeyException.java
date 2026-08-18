package io.cassyx.core.api.query;

import io.cassyx.core.api.CassyxCoreException;
import java.util.List;

/**
 * The operation needs the complete primary key and did not get it (plan section 7: "refuse to edit
 * any result set that doesn't project the complete primary key, and say why").
 *
 * <p>{@link #missingKeyColumns()} names exactly what is missing so the UI can explain the refusal
 * instead of just disabling the editor.
 */
public class IncompletePrimaryKeyException extends CassyxCoreException {

  private static final long serialVersionUID = 1L;

  private final transient List<String> missingKeyColumns;

  public IncompletePrimaryKeyException(String message, List<String> missingKeyColumns) {
    super(message);
    this.missingKeyColumns = missingKeyColumns == null ? List.of() : List.copyOf(missingKeyColumns);
  }

  public List<String> missingKeyColumns() {
    return missingKeyColumns;
  }
}
