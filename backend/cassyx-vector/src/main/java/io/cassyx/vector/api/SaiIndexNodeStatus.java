package io.cassyx.vector.api;

/**
 * Per-node SAI build state.
 *
 * <p>SAI builds independently on every replica, so a cluster-wide "queryable" is the AND of the
 * nodes - reporting only the coordinator's view is how an index looks ready while a replica is
 * still building.
 */
public record SaiIndexNodeStatus(String endpoint, SaiIndexState state) {}
