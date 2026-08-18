package io.cassyx.core.impl;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.CqlSessionBuilder;
import com.datastax.oss.driver.api.core.config.DefaultDriverOption;
import com.datastax.oss.driver.api.core.config.DriverConfigLoader;
import com.datastax.oss.driver.api.core.config.ProgrammaticDriverConfigLoaderBuilder;
import com.datastax.oss.driver.api.core.ssl.ProgrammaticSslEngineFactory;
import io.cassyx.core.api.CassyxCoreException;
import io.cassyx.core.api.ConnectionSpec;
import io.cassyx.core.api.SessionFactory;
import io.cassyx.core.api.SslSpec;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.function.Function;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

/**
 * Builds driver sessions for all three connection modes of plan section 3.
 *
 * <p>Astra bundles are supplied as an already-materialised local file by the caller-provided
 * resolver, which keeps this class free of any download / storage / decryption concern - and means
 * a bundle can come from the DevOps API, from H2, or from an allow-listed server path without this
 * class knowing which.
 *
 * <p>SSH tunnelling happens <i>before</i> this class runs: the caller opens the tunnel and passes a
 * spec whose contact points already point at the local forward. That keeps the driver entirely
 * unaware that SSH exists.
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
      applyAstra(builder, spec);
    } else if (spec.isAdvanced()) {
      applyAdvanced(builder, spec);
    } else {
      applyContactPoints(builder, spec);
    }

    if (spec.username() != null && !spec.isAstra()) {
      builder.withAuthCredentials(spec.username(), spec.password().reveal());
    }
    if (spec.defaultKeyspace() != null) {
      builder.withKeyspace(spec.defaultKeyspace());
    }
    // SSL is orthogonal to the mode, but Astra bundles carry their own mutual TLS material and
    // overriding it would break the connection rather than harden it.
    if (!spec.isAstra() && spec.ssl() != null && spec.ssl().enabled()) {
      builder.withSslEngineFactory(sslEngineFactory(spec.ssl()));
    }

    ProgrammaticDriverConfigLoaderBuilder config = driverOptions(spec);
    if (config != null) {
      builder.withConfigLoader(config.build());
    }

    try {
      return builder.build();
    } catch (RuntimeException e) {
      throw new CassyxCoreException(
          "Could not open a session for connection '" + spec.name() + "': " + e.getMessage(), e);
    }
  }

  private void applyAstra(CqlSessionBuilder builder, ConnectionSpec spec) {
    Path bundle = secureBundleResolver == null ? null : secureBundleResolver.apply(spec);
    if (bundle == null) {
      throw new CassyxCoreException(
          "No secure connect bundle available for connection '"
              + spec.name()
              + "'. Download it from your Astra token, upload the zip, or point at a server-side "
              + "path under CASSYX_SCB_PATH_ROOT.");
    }
    builder.withCloudSecureConnectBundle(bundle);
    // Astra authenticates with the literal username "token" and the AstraCS token as the password.
    builder.withAuthCredentials("token", spec.astra().token().reveal());
  }

  private void applyAdvanced(CqlSessionBuilder builder, ConnectionSpec spec) {
    try {
      builder.withConfigLoader(DriverConfigLoader.fromString(spec.advancedConfig()));
    } catch (RuntimeException e) {
      throw new CassyxCoreException(
          "The advanced configuration is not valid HOCON: " + e.getMessage(), e);
    }
    // Contact points may also be given alongside the HOCON; the driver merges them.
    for (String contactPoint : spec.contactPoints()) {
      builder.addContactPoint(parseContactPoint(contactPoint));
    }
    if (spec.localDatacenter() != null) {
      builder.withLocalDatacenter(spec.localDatacenter());
    }
  }

  private void applyContactPoints(CqlSessionBuilder builder, ConnectionSpec spec) {
    if (spec.contactPoints().isEmpty()) {
      throw new CassyxCoreException("Connection '" + spec.name() + "' has no contact points");
    }
    for (String contactPoint : spec.contactPoints()) {
      builder.addContactPoint(parseContactPoint(contactPoint));
    }
    if (spec.localDatacenter() != null) {
      builder.withLocalDatacenter(spec.localDatacenter());
    } else {
      throw new CassyxCoreException(
          "Connection '"
              + spec.name()
              + "' has no local datacenter. The driver's default load-balancing policy has no safe "
              + "default for it - use the datacenter name from `nodetool status`.");
    }
  }

  /** Null when the spec overrides nothing, so the driver's own defaults apply untouched. */
  private static ProgrammaticDriverConfigLoaderBuilder driverOptions(ConnectionSpec spec) {
    ProgrammaticDriverConfigLoaderBuilder config = null;
    if (spec.protocolVersion() != null) {
      config = DriverConfigLoader.programmaticBuilder();
      config.withString(DefaultDriverOption.PROTOCOL_VERSION, spec.protocolVersion());
    }
    if (spec.requestTimeout() != null) {
      config = config == null ? DriverConfigLoader.programmaticBuilder() : config;
      config.withDuration(DefaultDriverOption.REQUEST_TIMEOUT, spec.requestTimeout());
      config.withDuration(DefaultDriverOption.CONNECTION_INIT_QUERY_TIMEOUT, spec.requestTimeout());
    }
    return config;
  }

  /**
   * Builds the TLS material from the uploaded stores.
   *
   * <p>{@code hostnameValidation} is honoured rather than quietly forced on: some Cassandra
   * deployments issue node certificates with no matching SAN and there is no way to connect to them
   * with validation on. It defaults to true and the UI marks turning it off as a downgrade.
   */
  static ProgrammaticSslEngineFactory sslEngineFactory(SslSpec ssl) {
    try {
      SSLContext context = SSLContext.getInstance("TLS");
      context.init(keyManagers(ssl), trustManagers(ssl), null);
      String[] cipherSuites =
          ssl.cipherSuites().isEmpty() ? null : ssl.cipherSuites().toArray(new String[0]);
      return new ProgrammaticSslEngineFactory(context, cipherSuites, ssl.hostnameValidation());
    } catch (GeneralSecurityException | IOException e) {
      throw new CassyxCoreException(
          "The SSL material for this connection could not be loaded ("
              + e.getClass().getSimpleName()
              + "). Check the store type and the store password.",
          e);
    }
  }

  private static KeyManager[] keyManagers(SslSpec ssl)
      throws GeneralSecurityException, IOException {
    if (!ssl.hasKeystore()) {
      return null;
    }
    char[] password = ssl.keystorePassword().revealChars();
    KeyStore store = loadStore(ssl.keystore(), password);
    KeyManagerFactory factory =
        KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    factory.init(store, password);
    return factory.getKeyManagers();
  }

  private static TrustManager[] trustManagers(SslSpec ssl)
      throws GeneralSecurityException, IOException {
    if (!ssl.hasTruststore()) {
      return null;
    }
    KeyStore store = loadStore(ssl.truststore(), ssl.truststorePassword().revealChars());
    TrustManagerFactory factory =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    factory.init(store);
    return factory.getTrustManagers();
  }

  /** Tries PKCS12 then JKS, so the user never has to tell us which one they uploaded. */
  private static KeyStore loadStore(byte[] bytes, char[] password)
      throws GeneralSecurityException, IOException {
    GeneralSecurityException lastFailure = null;
    for (String type : new String[] {"PKCS12", "JKS"}) {
      try {
        KeyStore store = KeyStore.getInstance(type);
        store.load(new ByteArrayInputStream(bytes), password.length == 0 ? null : password);
        return store;
      } catch (IOException | GeneralSecurityException e) {
        lastFailure = new GeneralSecurityException("Could not read the store as " + type, e);
      }
    }
    throw lastFailure;
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
