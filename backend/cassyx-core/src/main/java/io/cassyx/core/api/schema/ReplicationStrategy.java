package io.cassyx.core.api.schema;

/** Keyspace replication strategies exposed by the keyspace editor. */
public enum ReplicationStrategy {
  SimpleStrategy,
  NetworkTopologyStrategy,
  EverywhereStrategy,
  LocalStrategy
}
