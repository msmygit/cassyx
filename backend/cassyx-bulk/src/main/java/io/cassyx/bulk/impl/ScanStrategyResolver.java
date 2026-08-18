package io.cassyx.bulk.impl;

import io.cassyx.bulk.api.ScanStrategy;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Decides between the token-range fast path and the paged fallback (plan section 7.1).
 *
 * <p>Amazon Keyspaces is the case that forces this to exist: it speaks CQL and looks like Cassandra
 * to the driver, but it has no token ring, exposes no token ranges, and rejects
 * {@code WHERE token(pk) > ?}. Erroring there would be a bug - the capability matrix says degrade,
 * do not break.
 *
 * <p>Pure and static so the decision table is unit-tested without a cluster.
 */
public final class ScanStrategyResolver {

  /** Option key that lets a caller force the fallback (used by the capability probe / the UI). */
  public static final String OPTION_STRATEGY = "scanStrategy";

  /** Host-name fragments that identify a Keyspaces endpoint. */
  private static final List<String> KEYSPACES_HOST_MARKERS =
      List.of("amazonaws.com", "cassandra.us-", "cassandra.eu-", "cassandra.ap-");

  private ScanStrategyResolver() {}

  /**
   * @param tokenRangeCount number of ranges the driver's token map reports; {@code 0} when the token
   *     map is absent or empty
   * @param endpoints rendered contact points / node endpoints
   * @param override value of {@link #OPTION_STRATEGY}, or {@code null}
   */
  public static ScanStrategy resolve(
      int tokenRangeCount, Collection<String> endpoints, String override) {
    if (override != null && !override.isBlank()) {
      String normalised = override.trim().toUpperCase(Locale.ROOT);
      if (ScanStrategy.PAGING.name().equals(normalised)) {
        return ScanStrategy.PAGING;
      }
      if (ScanStrategy.TOKEN_RANGE.name().equals(normalised)) {
        // An explicit request for the fast path still loses to a cluster that cannot serve it.
        return tokenRangeCount > 0 && !isKeyspaces(endpoints)
            ? ScanStrategy.TOKEN_RANGE
            : ScanStrategy.PAGING;
      }
      throw new IllegalArgumentException("Unknown scanStrategy '" + override + "'");
    }
    if (tokenRangeCount <= 0) {
      return ScanStrategy.PAGING;
    }
    return isKeyspaces(endpoints) ? ScanStrategy.PAGING : ScanStrategy.TOKEN_RANGE;
  }

  /** True when any endpoint looks like an Amazon Keyspaces host. */
  public static boolean isKeyspaces(Collection<String> endpoints) {
    if (endpoints == null) {
      return false;
    }
    for (String endpoint : endpoints) {
      if (endpoint == null) {
        continue;
      }
      String host = endpoint.toLowerCase(Locale.ROOT);
      for (String marker : KEYSPACES_HOST_MARKERS) {
        if (host.contains(marker)) {
          return true;
        }
      }
    }
    return false;
  }
}
