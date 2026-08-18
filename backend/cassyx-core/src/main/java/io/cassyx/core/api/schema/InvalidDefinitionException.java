package io.cassyx.core.api.schema;

import io.cassyx.core.api.CassyxCoreException;

/** A structured DDL definition is internally inconsistent - rejected before any CQL is generated. */
public class InvalidDefinitionException extends CassyxCoreException {

  private static final long serialVersionUID = 1L;

  private final String field;

  public InvalidDefinitionException(String field, String message) {
    super(message);
    this.field = field;
  }

  /** The offending field, for the contract's per-field validation errors. */
  public String field() {
    return field;
  }
}
