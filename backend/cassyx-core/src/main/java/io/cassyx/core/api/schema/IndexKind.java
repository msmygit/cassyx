package io.cassyx.core.api.schema;

/**
 * {@code SAI} is the primary path on Cassandra 5.x / Astra; {@code COMPOSITES}/{@code KEYS}/
 * {@code CUSTOM} are legacy 2i; {@code DSE_SEARCH} is DSE-only (plan sections 4 and 6).
 */
public enum IndexKind {
  SAI,
  COMPOSITES,
  KEYS,
  CUSTOM,
  DSE_SEARCH
}
