package io.cassyx.core.api;

/** The heterogeneous target list of plan section 7.1. */
public enum ClusterFlavor {
  CASSANDRA,
  DSE,
  ASTRA,
  AMAZON_KEYSPACES,
  SCYLLA,
  UNKNOWN
}
