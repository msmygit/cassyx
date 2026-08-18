package io.cassyx.core.api;

import com.datastax.oss.driver.api.core.CqlSession;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * SPI - discovered with {@link ServiceLoader}. Adding support for another CQL-compatible store
 * means adding one class plus a {@code META-INF/services} entry, not editing an if/else chain
 * (plan section 2.1).
 */
public interface CapabilityProbe {

  /** Stable identifier, used for logging and for deterministic ordering. */
  String id();

  /** Probes ordered ascending; the first probe that claims the cluster wins. */
  default int priority() {
    return 100;
  }

  /** @return the detected capabilities, or empty if this probe does not recognise the cluster */
  Optional<ClusterCapabilities> probe(CqlSession session);

  /** Loads all probes visible to this module's class loader, ordered by {@link #priority()}. */
  static ServiceLoader<CapabilityProbe> load() {
    return ServiceLoader.load(CapabilityProbe.class);
  }
}
