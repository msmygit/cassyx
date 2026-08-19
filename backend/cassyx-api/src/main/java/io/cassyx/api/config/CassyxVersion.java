package io.cassyx.api.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;

/**
 * The running application's version, resolved once and shared.
 *
 * <p>It used to be a hardcoded {@code @Value("${cassyx.version:0.1.0-SNAPSHOT}")} default in two
 * unrelated controllers, and {@code cassyx.version} was never actually set anywhere - so the string
 * was guaranteed to be wrong the moment the reactor version moved. That is not cosmetic here: the
 * licence gate derives {@code UPGRADE_REQUIRED} from the running MAJOR version (plan section 9.5),
 * and a gate that refuses requests on the strength of a wrong version string is worse than no gate.
 *
 * <p>Source of truth is {@link BuildProperties}, produced by the {@code build-info} goal of
 * spring-boot-maven-plugin, which reads the real Maven coordinates at package time. That bean is
 * absent when running from an IDE (no {@code build-info.properties} on the classpath), so it
 * degrades: an explicit {@code cassyx.version} property wins if set, then build-info, then Spring
 * Boot's own {@code spring.application.version}, then {@link #DEFAULT}.
 */
public final class CassyxVersion {

  /**
   * Used when nothing on the classpath knows the version.
   *
   * <p>It is {@code 1.0.0}, not {@code 0.x} and not a sentinel, because of how it is consumed:
   * {@link io.cassyx.license.api.License#coversMajor(int)} answers true for EVERY scope when the
   * running major is 0, so a degraded default of {@code 0.1.0-SNAPSHOT} would make version scoping
   * (plan section 9.5) fail silently OPEN - every future major unlocked by a key sold for v1, with
   * nothing in a log to say so. The degraded path must fail towards the correct major.
   */
  public static final String DEFAULT = "1.0.0";

  /** Major used when the version string carries no leading integer. Same reasoning as {@link #DEFAULT}. */
  public static final int DEFAULT_MAJOR = 1;

  private final String value;
  private final int major;

  /** {@code @Autowired} because the private constructor below is also a candidate. */
  @Autowired
  public CassyxVersion(
      ObjectProvider<BuildProperties> buildProperties,
      @Value("${cassyx.version:}") String configured,
      @Value("${spring.application.version:}") String springApplicationVersion) {
    this(
        firstPresent(
            configured,
            buildProperties.getIfAvailable() == null ? null : buildProperties.getObject().getVersion(),
            springApplicationVersion));
  }

  private CassyxVersion(String value) {
    this.value = value == null || value.isBlank() ? DEFAULT : value.trim();
    this.major = majorOf(this.value);
  }

  /** For tests and for the callers that already hold a version string. */
  public static CassyxVersion of(String value) {
    return new CassyxVersion(value);
  }

  public String value() {
    return value;
  }

  /** {@code 1.4.2} -> {@code 1}; {@link #DEFAULT_MAJOR} when the string carries no leading integer. */
  public int major() {
    return major;
  }

  private static String firstPresent(String... candidates) {
    for (String candidate : candidates) {
      if (candidate != null && !candidate.isBlank()) {
        return candidate;
      }
    }
    return null;
  }

  private static int majorOf(String version) {
    try {
      return Integer.parseInt(version.split("[.\\-+]")[0]);
    } catch (NumberFormatException e) {
      return DEFAULT_MAJOR;
    }
  }

  @Override
  public String toString() {
    return value;
  }
}
