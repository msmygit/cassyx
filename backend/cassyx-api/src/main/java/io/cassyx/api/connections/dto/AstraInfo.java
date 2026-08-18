package io.cassyx.api.connections.dto;

/** Response-side view of the Astra settings - never contains the token. */
public record AstraInfo(
    boolean hasAstraToken,
    ScbMode scbMode,
    String databaseId,
    String databaseName,
    String region,
    ScbType scbType,
    String domain,
    String scbPath,
    SecureConnectBundleInfo secureConnectBundle) {}
