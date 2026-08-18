package io.cassyx.core.api.schema;

import io.cassyx.core.api.CassyxCoreException;

/** The referenced keyspace / table / view / type / function does not exist. */
public class SchemaNotFoundException extends CassyxCoreException {

  private static final long serialVersionUID = 1L;

  private final transient SchemaIdentity identity;

  public SchemaNotFoundException(String message) {
    this(message, null);
  }

  public SchemaNotFoundException(String message, SchemaIdentity identity) {
    super(message);
    this.identity = identity;
  }

  /** The object that was not found, when it is known. */
  public SchemaIdentity identity() {
    return identity;
  }
}
