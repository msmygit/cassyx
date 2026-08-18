package io.cassyx.core.api.schema;

/** The object a generated DDL statement targets. */
public enum DdlObjectType {
  KEYSPACE,
  TABLE,
  COLUMN,
  INDEX,
  MATERIALIZED_VIEW,
  TYPE,
  FUNCTION,
  AGGREGATE,
  ROLE,
  PERMISSION
}
