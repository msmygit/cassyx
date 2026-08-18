package io.cassyx.core.api.schema;

/** Mirrors the contract's {@code SchemaNodeKind}. */
public enum SchemaObjectKind {
  KEYSPACE,
  TABLE,
  VIEW,
  COLUMN,
  INDEX,
  TYPE,
  FUNCTION,
  AGGREGATE,
  ROLE
}
