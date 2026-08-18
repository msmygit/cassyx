package io.cassyx.bulk.api;

/**
 * How the unload engine reads the table.
 *
 * <p>{@link #TOKEN_RANGE} is the fast path of plan section 5.2. {@link #PAGING} is the mandatory
 * fallback of plan section 7.1: Amazon Keyspaces exposes no token ring and rejects
 * {@code WHERE token(pk) > ?}, so there the engine degrades to a single paged full scan rather than
 * erroring.
 */
public enum ScanStrategy {
  TOKEN_RANGE,
  PAGING
}
