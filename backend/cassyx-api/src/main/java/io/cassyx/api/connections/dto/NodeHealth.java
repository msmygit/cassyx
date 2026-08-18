package io.cassyx.api.connections.dto;

/** One node's state, read from driver metadata - no query is issued. */
public record NodeHealth(
    String endpoint,
    String state,
    String datacenter,
    String rack,
    String cassandraVersion,
    String hostId,
    int openConnections) {}
