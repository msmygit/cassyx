package io.cassyx.api.smoke;

import static org.assertj.core.api.Assertions.assertThat;

import io.cassyx.api.bulk.DsbulkJobService;
import io.cassyx.api.config.BillingProperties;
import io.cassyx.api.config.LicenseProperties;
import io.cassyx.api.schema.SchemaSessions;
import io.cassyx.bulk.api.Encoder;
import io.cassyx.core.api.CqlStatementSplitter;
import io.cassyx.core.api.ManagedSessionRegistry;
import io.cassyx.core.api.SchemaCatalog;
import io.cassyx.core.api.SessionFactory;
import io.cassyx.core.api.SessionRegistry;
import io.cassyx.core.api.astra.ScbPathResolver;
import io.cassyx.license.api.LicenseVerifier;
import io.cassyx.license.api.PaymentProvider;
import io.cassyx.migrate.api.ImportSource;
import io.cassyx.vector.api.AnnQueryBuilder;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * Boots the whole application context, which also proves the Flyway baseline migration applies
 * cleanly to H2 and that the module SPIs wire up.
 */
@SpringBootTest
@TestPropertySource(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:cassyx-smoke;DB_CLOSE_DELAY=-1",
      "cassyx.license.enforce=false"
    })
class ApplicationContextSmokeTest {

  @Autowired private DataSource dataSource;
  @Autowired private SessionFactory sessionFactory;
  @Autowired private SchemaCatalog schemaCatalog;
  @Autowired private CqlStatementSplitter statementSplitter;
  @Autowired private ScbPathResolver scbPathResolver;
  @Autowired private AnnQueryBuilder annQueryBuilder;
  @Autowired private LicenseVerifier licenseVerifier;
  @Autowired private PaymentProvider paymentProvider;
  @Autowired private LicenseProperties licenseProperties;
  @Autowired private BillingProperties billingProperties;
  @Autowired private List<Encoder> encoders;
  @Autowired private List<ImportSource> importSources;
  @Autowired private ApplicationContext context;
  @Autowired private SchemaSessions schemaSessions;
  @Autowired private DsbulkJobService dsbulkJobService;

  @Test
  void wiresEveryModuleThroughItsApiPackage() {
    assertThat(sessionFactory).isNotNull();
    assertThat(schemaCatalog).isNotNull();
    assertThat(statementSplitter).isNotNull();
    assertThat(scbPathResolver).isNotNull();
    assertThat(annQueryBuilder).isNotNull();
    assertThat(licenseVerifier).isNotNull();
    assertThat(encoders).extracting(Encoder::format).contains("csv");
    assertThat(importSources).extracting(ImportSource::id).contains("csv");
  }

  /**
   * Exactly ONE {@code SessionRegistry} bean, and it is the real one.
   *
   * <p>Asserted rather than assumed because both failure modes have already happened here. Two beans
   * made every {@code SessionRegistry} injection point ambiguous and broke three workstreams' Spring
   * contexts at once. One bean that happened to be a no-op fallback was worse: nothing failed, and
   * the query, schema and DSBulk features quietly reported "not connected" and derived their
   * defaults from {@code UNKNOWN} against a cluster that was, in fact, connected.
   */
  @Test
  void exactlyOneSessionRegistryBeanIsPublished() {
    assertThat(context.getBeanNamesForType(SessionRegistry.class)).hasSize(1);
    assertThat(context.getBean(SessionRegistry.class)).isInstanceOf(ManagedSessionRegistry.class);
    assertThat(context.getBeanNamesForType(ManagedSessionRegistry.class)).hasSize(1);
    // The features that used to take it optionally now take it outright, so the context itself is
    // the proof that they got the real registry.
    assertThat(schemaSessions).isNotNull();
    assertThat(dsbulkJobService).isNotNull();
  }

  @Test
  void flywayCreatesTheBaselineSchema() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    List<String> tables =
        jdbc.queryForList(
            "SELECT table_name FROM information_schema.tables WHERE table_schema = 'PUBLIC'",
            String.class);

    assertThat(tables)
        .map(String::toLowerCase)
        .contains(
            "cassyx_connection",
            "cassyx_script_folder",
            "cassyx_saved_script",
            "cassyx_query_history",
            "cassyx_job",
            "cassyx_job_template",
            "cassyx_license",
            "cassyx_billing_event");
  }

  @Test
  void licenseAndBillingPlaceholdersAreBound() {
    assertThat(licenseProperties.enforce()).isFalse();
    assertThat(billingProperties.enabled()).isFalse();
    assertThat(billingProperties.provider()).isEqualTo("stripe");
    assertThat(billingProperties.apiBaseUrl()).isEqualTo("https://api.stripe.com");
    assertThat(billingProperties.publishableKey()).isEqualTo("pk_test_PLACEHOLDER");
    assertThat(billingProperties.secretKey()).startsWith("rk_");
    assertThat(billingProperties.webhookSecret()).isEqualTo("whsec_PLACEHOLDER");
    assertThat(billingProperties.priceId()).isEqualTo("price_PLACEHOLDER");
    assertThat(billingProperties.cancelUrl()).contains("/pricing");
    // Billing disabled => the noop provider is selected (plan section 9.3).
    assertThat(paymentProvider.id()).isEqualTo("noop");
  }
}
