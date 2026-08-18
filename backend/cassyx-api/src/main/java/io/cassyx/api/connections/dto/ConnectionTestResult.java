package io.cassyx.api.connections.dto;

import java.util.List;
import org.springframework.http.ProblemDetail;

/**
 * The result of a throwaway connect.
 *
 * <p>A failed probe is still a {@code 200} with {@code success: false}. Returning a 4xx/5xx for
 * "your contact points are wrong" would make the browser's generic error handling swallow the
 * diagnostic detail that is the entire point of a Test button.
 */
public record ConnectionTestResult(
    boolean success,
    long elapsedMillis,
    String clusterName,
    String releaseVersion,
    String partitioner,
    List<String> datacenters,
    Integer nodeCount,
    String protocolVersion,
    ClusterCapabilitiesView capabilities,
    ProblemDetail problem) {

  public ConnectionTestResult {
    datacenters = datacenters == null ? List.of() : List.copyOf(datacenters);
  }

  public static ConnectionTestResult failed(long elapsedMillis, ProblemDetail problem) {
    return new ConnectionTestResult(
        false, elapsedMillis, null, null, null, List.of(), null, null, null, problem);
  }
}
