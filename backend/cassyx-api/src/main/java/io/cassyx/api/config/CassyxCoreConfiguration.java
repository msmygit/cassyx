package io.cassyx.api.config;

import io.cassyx.bulk.api.BulkFactory;
import io.cassyx.bulk.api.Encoder;
import io.cassyx.bulk.api.Sink;
import io.cassyx.core.api.CoreFactory;
import io.cassyx.core.api.CqlStatementSplitter;
import io.cassyx.core.api.QueryExecutor;
import io.cassyx.core.api.SchemaCatalog;
import io.cassyx.core.api.SessionFactory;
import io.cassyx.core.api.astra.ScbPathResolver;
import io.cassyx.license.api.LicenseFactory;
import io.cassyx.license.api.LicenseStatus;
import io.cassyx.license.api.LicenseVerifier;
import io.cassyx.license.api.PaymentProvider;
import io.cassyx.migrate.api.ImportSource;
import io.cassyx.migrate.api.MigrateFactory;
import io.cassyx.vector.api.AnnQueryBuilder;
import io.cassyx.vector.api.SaiIndexManager;
import io.cassyx.vector.api.VectorFactory;
import java.nio.file.Paths;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The composition root.
 *
 * <p>Every bean here comes from a module's {@code ...api} factory - never from an {@code ...impl}
 * package. That is the second half of the modularity contract (plan section 2.1) and it is
 * ArchUnit-enforced: {@code cassyx-api} must not import any sibling's implementation package.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
  LicenseProperties.class,
  BillingProperties.class,
  ScbProperties.class
})
public class CassyxCoreConfiguration {

  private static final Logger LOG = LoggerFactory.getLogger(CassyxCoreConfiguration.class);

  @Bean
  public SessionFactory sessionFactory() {
    return CoreFactory.sessionFactory();
  }

  @Bean
  public SchemaCatalog schemaCatalog() {
    return CoreFactory.schemaCatalog();
  }

  @Bean
  public QueryExecutor queryExecutor() {
    return CoreFactory.queryExecutor();
  }

  @Bean
  public CqlStatementSplitter cqlStatementSplitter() {
    return CoreFactory.statementSplitter();
  }

  @Bean
  public ScbPathResolver scbPathResolver(ScbProperties properties) {
    String root =
        properties.pathRoot() == null || properties.pathRoot().isBlank()
            ? ScbPathResolver.DEFAULT_ROOT
            : properties.pathRoot();
    return CoreFactory.scbPathResolver(Paths.get(root));
  }

  /** ServiceLoader-discovered output formats (plan section 5.2). */
  @Bean
  public List<Encoder> encoders() {
    List<Encoder> encoders = BulkFactory.encoders();
    LOG.info("Registered {} bulk encoder(s): {}", encoders.size(), encoders.stream().map(Encoder::format).toList());
    return encoders;
  }

  /** ServiceLoader-discovered unload sinks (plan section 5.2). */
  @Bean
  public List<Sink> sinks() {
    return BulkFactory.sinks();
  }

  /** ServiceLoader-discovered import origins (plan section 8). */
  @Bean
  public List<ImportSource> importSources() {
    return MigrateFactory.importSources();
  }

  @Bean
  public AnnQueryBuilder annQueryBuilder() {
    return VectorFactory.annQueryBuilder();
  }

  @Bean
  public SaiIndexManager saiIndexManager() {
    return VectorFactory.saiIndexManager();
  }

  /**
   * Verifier over the embedded PUBLIC key. When no key is configured the verifier rejects every
   * license rather than failing startup - an unlicensed instance must still boot far enough to show
   * the activation screen.
   */
  @Bean
  public LicenseVerifier licenseVerifier(LicenseProperties properties) {
    String publicKey = properties.publicKey();
    if (publicKey == null || publicKey.isBlank() || publicKey.contains("PLACEHOLDER")) {
      LOG.warn("No CASSYX_LICENSE_PUBLIC_KEY configured; every license key will be rejected");
      return key -> LicenseStatus.invalid("No license public key configured");
    }
    return LicenseFactory.verifier(publicKey);
  }

  /**
   * Selects the payment provider by id (plan section 9.3). Falls back to {@code noop} when billing
   * is disabled or the configured provider has no implementation on the classpath.
   */
  @Bean
  public PaymentProvider paymentProvider(BillingProperties properties) {
    String id = properties.enabled() ? properties.provider() : "noop";
    try {
      return LicenseFactory.paymentProvider(id);
    } catch (IllegalArgumentException e) {
      LOG.warn("No PaymentProvider '{}' on the classpath; falling back to 'noop'", id);
      return LicenseFactory.paymentProvider("noop");
    }
  }
}
