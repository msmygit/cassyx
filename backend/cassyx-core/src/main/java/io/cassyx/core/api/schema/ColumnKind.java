package io.cassyx.core.api.schema;

/** Mirrors the contract's {@code ColumnKind}. {@code STATIC} is first-class (plan section 4). */
public enum ColumnKind {
  PARTITION_KEY,
  CLUSTERING,
  REGULAR,
  STATIC,
  COMPACT_VALUE
}
