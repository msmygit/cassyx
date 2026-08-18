package io.cassyx.core.api.schema;

/** CQL permissions for the GRANT/REVOKE matrix. */
public enum CqlPermission {
  ALL,
  CREATE,
  ALTER,
  DROP,
  SELECT,
  MODIFY,
  AUTHORIZE,
  DESCRIBE,
  EXECUTE
}
