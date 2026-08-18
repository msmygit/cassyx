package io.cassyx.core.api.schema;

/** The verb of a generated DDL statement. */
public enum DdlAction {
  CREATE,
  ALTER,
  DROP,
  TRUNCATE,
  GRANT,
  REVOKE
}
