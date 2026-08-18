package io.cassyx.core.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable description of how to reach a CQL cluster (plan section 3).
 *
 * <p>Exactly one of {@code contactPoints} (Cassandra/DSE mode) or {@code astra} (Astra mode) is
 * expected to be populated; {@code advancedConfig} carries raw HOCON for the advanced mode.
 * Secrets are held here only in transit - persistence encrypts them (AES-256-GCM) and the API
 * layer never returns them.
 */
public record ConnectionSpec(
    String name,
    List<String> contactPoints,
    String localDatacenter,
    String username,
    Secret password,
    Integer protocolVersion,
    AstraConnection astra,
    String advancedConfig) {

  public ConnectionSpec {
    Objects.requireNonNull(name, "name");
    contactPoints = contactPoints == null ? List.of() : List.copyOf(contactPoints);
    password = password == null ? Secret.empty() : password;
  }

  /** The presence flag the API exposes instead of the value (plan section 2.3). */
  public boolean hasPassword() {
    return password.isPresent();
  }

  public static ConnectionSpec cassandra(
      String name, List<String> contactPoints, String localDatacenter) {
    return new ConnectionSpec(name, contactPoints, localDatacenter, null, null, null, null, null);
  }

  public static ConnectionSpec astra(String name, AstraConnection astra) {
    return new ConnectionSpec(name, List.of(), null, null, null, null, astra, null);
  }

  public Optional<AstraConnection> astraConnection() {
    return Optional.ofNullable(astra);
  }

  public boolean isAstra() {
    return astra != null;
  }

  /** Never expose credentials through toString - this object ends up in logs and job records. */
  @Override
  public String toString() {
    return "ConnectionSpec[name="
        + name
        + ", contactPoints="
        + contactPoints
        + ", localDatacenter="
        + localDatacenter
        + ", username="
        + (username == null ? "<none>" : "<set>")
        + ", password="
        + password
        + ", astra="
        + (astra == null ? "<none>" : astra)
        + "]";
  }
}
