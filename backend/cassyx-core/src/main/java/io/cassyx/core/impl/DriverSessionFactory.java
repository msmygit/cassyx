package io.cassyx.core.impl;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.CqlSessionBuilder;
import io.cassyx.core.api.CassyxCoreException;
import io.cassyx.core.api.ConnectionSpec;
import io.cassyx.core.api.SessionFactory;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.function.Function;

/**
 * Builds driver sessions. Astra bundles are supplied as an already-materialised local file by the
 * caller-provided resolver, which keeps this class free of any download / storage concern.
 */
public final class DriverSessionFactory implements SessionFactory {

  private final Function<ConnectionSpec, Path> secureBundleResolver;

  public DriverSessionFactory() {
    this(spec -> null);
  }

  /**
   * @param secureBundleResolver returns the local path of the secure connect bundle for an Astra
   *     spec (download, decrypt-from-H2 or allow-listed path), or null for non-Astra specs
   */
  public DriverSessionFactory(Function<ConnectionSpec, Path> secureBundleResolver) {
    this.secureBundleResolver = secureBundleResolver;
  }

  @Override
  public CqlSession open(ConnectionSpec spec) {
    CqlSessionBuilder builder = CqlSession.builder();
    if (spec.isAstra()) {
      Path bundle = secureBundleResolver.apply(spec);
      if (bundle == null) {
        throw new CassyxCoreException(
            "No secure connect bundle available for connection '" + spec.name() + "'");
      }
      builder.withCloudSecureConnectBundle(bundle);
      builder.withAuthCredentials("token", spec.astra().token().reveal());
    } else {
      if (spec.contactPoints().isEmpty()) {
        throw new CassyxCoreException(
            "Connection '" + spec.name() + "' has no contact points");
      }
      for (String cp : spec.contactPoints()) {
        builder.addContactPoint(parseContactPoint(cp));
      }
      if (spec.localDatacenter() != null && !spec.localDatacenter().isBlank()) {
        builder.withLocalDatacenter(spec.localDatacenter());
      }
      if (spec.username() != null && !spec.username().isBlank()) {
        builder.withAuthCredentials(spec.username(), spec.password().reveal());
      }
    }
    try {
      return builder.build();
    } catch (RuntimeException e) {
      throw new CassyxCoreException(
          "Could not open a session for connection '" + spec.name() + "': " + e.getMessage(), e);
    }
  }

  /** Visible for testing. Accepts {@code host:port}, defaulting to 9042. */
  public static InetSocketAddress parseContactPoint(String contactPoint) {
    if (contactPoint == null || contactPoint.isBlank()) {
      throw new CassyxCoreException("Blank contact point");
    }
    String value = contactPoint.trim();
    int idx = value.lastIndexOf(':');
    if (idx < 0) {
      return InetSocketAddress.createUnresolved(value, 9042);
    }
    String host = value.substring(0, idx);
    try {
      return InetSocketAddress.createUnresolved(host, Integer.parseInt(value.substring(idx + 1)));
    } catch (NumberFormatException e) {
      throw new CassyxCoreException("Invalid contact point '" + contactPoint + "'", e);
    }
  }
}
