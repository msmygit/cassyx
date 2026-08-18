package io.cassyx.api.connections.dto;

/**
 * The three connection modes of plan section 3, as the contract's {@code ConnectionMode} enum.
 *
 * <p>{@code CASSANDRA} and {@code DSE} are the same wire shape - contact points, a local datacenter
 * and optional credentials. They are separate values because the UI and the capability probe both
 * benefit from knowing which one the user believes they are connecting to.
 */
public enum ConnectionMode {
  CASSANDRA,
  DSE,
  ASTRA,
  ADVANCED;

  public boolean isAstra() {
    return this == ASTRA;
  }

  public boolean isDirect() {
    return this == CASSANDRA || this == DSE;
  }
}
