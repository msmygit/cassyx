package io.cassyx.core.api;

/**
 * Which bulk read strategy the detected cluster supports (plan section 7.1).
 *
 * <p>Amazon Keyspaces has no {@code token()} range scan, so the section 5.2 engine cannot be
 * pointed at it and must fall back to plain driver paging. Surfacing this as an explicit value
 * rather than leaving each engine to re-derive it from {@link Capability#TOKEN_RANGE_SCAN} means
 * the fallback decision is made once, at probe time.
 */
public enum BulkReadStrategy {
  TOKEN_RANGE_SCAN,
  PLAIN_PAGING
}
