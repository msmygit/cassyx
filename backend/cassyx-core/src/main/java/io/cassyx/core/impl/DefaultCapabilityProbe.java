package io.cassyx.core.impl;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.metadata.Node;
import io.cassyx.core.api.Capability;
import io.cassyx.core.api.CapabilityProbe;
import io.cassyx.core.api.ClusterCapabilities;
import io.cassyx.core.api.ClusterFlavor;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * Fallback probe: recognises open-source Apache Cassandra from the release version reported by the
 * driver's node metadata and applies the plan section 7.1 matrix. Registered last (highest
 * priority number) so vendor-specific probes added later win.
 */
public final class DefaultCapabilityProbe implements CapabilityProbe {

  @Override
  public String id() {
    return "apache-cassandra";
  }

  @Override
  public int priority() {
    return 1000;
  }

  @Override
  public Optional<ClusterCapabilities> probe(CqlSession session) {
    String version =
        session.getMetadata().getNodes().values().stream()
            .map(Node::getCassandraVersion)
            .filter(java.util.Objects::nonNull)
            .map(Object::toString)
            .findFirst()
            .orElse("unknown");
    return Optional.of(new ClusterCapabilities(ClusterFlavor.CASSANDRA, version, forVersion(version)));
  }

  /** Visible for testing: capability set for an Apache Cassandra release string. */
  public static Set<Capability> forVersion(String version) {
    Set<Capability> caps =
        EnumSet.of(
            Capability.MATERIALIZED_VIEWS,
            Capability.UDF_UDA,
            Capability.TRUNCATE,
            Capability.TOKEN_RANGE_SCAN,
            Capability.ROLES_PERMISSIONS);
    if (majorVersion(version) >= 5) {
      caps.add(Capability.SAI);
      caps.add(Capability.VECTOR_ANN);
    }
    return Set.copyOf(caps);
  }

  private static int majorVersion(String version) {
    if (version == null) {
      return 0;
    }
    int dot = version.indexOf('.');
    String major = dot < 0 ? version : version.substring(0, dot);
    try {
      return Integer.parseInt(major.trim());
    } catch (NumberFormatException e) {
      return 0;
    }
  }
}
