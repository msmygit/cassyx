package io.cassyx.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;

/**
 * The version the app reports is load-bearing, not cosmetic: {@code License.coversMajor()} returns
 * true for every possible scope when the running major is {@code 0}, so a version that degrades
 * towards {@code 0.x} makes version scoping (plan section 9.5) fail silently OPEN - every future
 * major unlocked by a key sold for v1, with nothing in a log to say so.
 */
class CassyxVersionTest {

  /** Written by the {@code build-info} goal of spring-boot-maven-plugin at generate-resources. */
  private static final String BUILD_INFO = "/META-INF/build-info.properties";

  @Test
  @DisplayName("build-info is on the classpath and reports major 1")
  void theBuiltArtifactReportsTheRealVersion() throws Exception {
    // Deliberately NOT skipped when the resource is missing. This is the assertion that turns "a
    // packaging change quietly unlocked every future major" into a red build.
    try (InputStream in = CassyxVersionTest.class.getResourceAsStream(BUILD_INFO)) {
      assertThat(in)
          .as(
              "%s must be produced by spring-boot-maven-plugin:build-info; without it the app "
                  + "falls back to a default version and cannot report its real major",
              BUILD_INFO)
          .isNotNull();

      Properties properties = new Properties();
      properties.load(in);
      String version = properties.getProperty("build.version");

      assertThat(CassyxVersion.of(version).major())
          .as("build.version=%s must parse to major 1", version)
          .isEqualTo(1);
    }
  }

  @Test
  @DisplayName("With build-info absent, the version degrades to 1.0.0 rather than to major 0")
  void degradedPathFailsTowardsTheCorrectMajor() {
    CassyxVersion version = new CassyxVersion(none(), "", "");

    assertThat(version.value()).isEqualTo(CassyxVersion.DEFAULT);
    assertThat(version.major()).isEqualTo(1);
  }

  @Test
  @DisplayName("build-info wins over Spring Boot's manifest-derived version")
  void buildInfoIsThePreferredSource() {
    Properties properties = new Properties();
    properties.setProperty("version", "3.2.1");
    CassyxVersion version =
        new CassyxVersion(provider(new BuildProperties(properties)), "", "9.9.9");

    assertThat(version.value()).isEqualTo("3.2.1");
    assertThat(version.major()).isEqualTo(3);
  }

  @Test
  @DisplayName("An explicit cassyx.version overrides everything, for the operator who needs it")
  void explicitPropertyWins() {
    Properties properties = new Properties();
    properties.setProperty("version", "3.2.1");

    assertThat(new CassyxVersion(provider(new BuildProperties(properties)), "4.0.0", "").value())
        .isEqualTo("4.0.0");
  }

  @Test
  @DisplayName("An unparseable version still yields major 1, never 0")
  void unparseableVersionDoesNotFailOpen() {
    assertThat(CassyxVersion.of("nightly").major()).isEqualTo(CassyxVersion.DEFAULT_MAJOR);
    assertThat(CassyxVersion.of("1.0.0-SNAPSHOT").major()).isEqualTo(1);
    assertThat(CassyxVersion.of("2.7.0").major()).isEqualTo(2);
  }

  private static ObjectProvider<BuildProperties> none() {
    return new SingleObjectProvider(null);
  }

  private static ObjectProvider<BuildProperties> provider(BuildProperties properties) {
    return new SingleObjectProvider(properties);
  }

  /** Minimal ObjectProvider; Spring offers no public no-op implementation to reuse. */
  private record SingleObjectProvider(BuildProperties value)
      implements ObjectProvider<BuildProperties> {

    @Override
    public BuildProperties getObject() {
      return value;
    }

    @Override
    public BuildProperties getObject(Object... args) {
      return value;
    }

    @Override
    public BuildProperties getIfAvailable() {
      return value;
    }

    @Override
    public BuildProperties getIfUnique() {
      return value;
    }
  }
}
