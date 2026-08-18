package io.cassyx.api.connections.dto;

import io.cassyx.core.api.CapabilityStatus;

/**
 * One capability as the contract renders it.
 *
 * @param reason shown verbatim as the tooltip on a hidden or caveated feature (plan section 7.1)
 */
public record CapabilityView(String name, String support, String reason, String since) {

  public static CapabilityView from(CapabilityStatus status) {
    return new CapabilityView(
        status.capability().wireName(),
        status.support().name(),
        status.reason(),
        status.since());
  }
}
