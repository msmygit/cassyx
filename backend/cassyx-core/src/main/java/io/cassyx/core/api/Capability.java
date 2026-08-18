package io.cassyx.core.api;

/** Feature gates driven by the capability matrix in plan section 7.1. */
public enum Capability {
  SAI,
  VECTOR_ANN,
  MATERIALIZED_VIEWS,
  UDF_UDA,
  TRUNCATE,
  TOKEN_RANGE_SCAN,
  DSE_SEARCH,
  ROLES_PERMISSIONS
}
