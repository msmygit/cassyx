package io.cassyx.vector.api;

/** Build state of an SAI index, per node and aggregated. */
public enum SaiIndexState {
  BUILDING,
  QUERYABLE,
  FAILED,
  UNKNOWN
}
