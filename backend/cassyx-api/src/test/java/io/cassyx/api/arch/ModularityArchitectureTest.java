package io.cassyx.api.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * THE modularity contract of plan section 2.1, enforced.
 *
 * <p>This test lives in {@code cassyx-api} because that is the only module with every other module
 * on its classpath, so one import sees the whole product. It runs in the default build ({@code mvn
 * test}) and in the CI {@code arch} job.
 *
 * <p>Two rules:
 *
 * <ol>
 *   <li><b>No Spring below cassyx-api.</b> Every lower module is a plain Java library with
 *       constructor injection, usable in an unrelated project with nothing but a {@code CqlSession}.
 *   <li><b>No module depends on a sibling's implementation package.</b> Cross-module traffic goes
 *       through {@code ...api} only. Each module exposes a factory inside its own {@code ...api}
 *       package as the single seam to its {@code ...impl} classes.
 * </ol>
 *
 * <p>Both rules were verified to actually FAIL when violated before being committed - a green
 * architecture test that cannot go red is worse than none.
 */
class ModularityArchitectureTest {

  private static final String ROOT = "io.cassyx";

  /** Modules whose implementation packages are private to themselves. */
  private static final List<String> LIBRARY_MODULES =
      List.of("core", "bulk", "vector", "migrate", "license");

  private static final JavaClasses PRODUCTION_CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          // Sibling modules contribute a test-jar (the shared Testcontainers singleton), and their
          // test classes are not production code - whether they resolve to target/test-classes in
          // a reactor build or to a *-tests.jar from the local repository.
          .withImportOption(
              location -> !location.contains("test-classes") && !location.contains("-tests.jar"))
          // Deliberately NOT DO_NOT_INCLUDE_JARS: in a reactor build the siblings are directories,
          // but in a partial build they are jars, and skipping them would make this test pass
          // vacuously. everyLibraryModuleHasAnApiPackage() guards against an empty import.
          .importPackages(ROOT);

  @Test
  @DisplayName("Spring is confined to cassyx-api (plan 2.1)")
  void springOnlyInApiModule() {
    ArchRule rule =
        noClasses()
            .that()
            .resideOutsideOfPackage("io.cassyx.api..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("org.springframework..", "jakarta.servlet..")
            .because(
                "cassyx-core/-bulk/-vector/-migrate/-license must be usable without Spring, "
                    + "without the web layer and without the UI (plan section 2.1). "
                    + "Use constructor injection and let cassyx-api supply the @Bean wiring.");

    rule.check(PRODUCTION_CLASSES);
  }

  @Test
  @DisplayName("No module reaches into a sibling's impl package (plan 2.1)")
  void siblingsDependOnApiPackagesOnly() {
    for (String module : LIBRARY_MODULES) {
      String modulePackage = "io.cassyx." + module + "..";
      String implPackage = "io.cassyx." + module + ".impl..";

      ArchRule rule =
          noClasses()
              .that()
              .resideOutsideOfPackage(modulePackage)
              .should()
              .dependOnClassesThat()
              .resideInAPackage(implPackage)
              .because(
                  "only the ...api package of cassyx-"
                      + module
                      + " is public surface (plan section 2.1). Add a factory method to "
                      + "io.cassyx."
                      + module
                      + ".api instead of importing the implementation.");

      rule.check(PRODUCTION_CLASSES);
    }
  }

  @Test
  @DisplayName("Every library module exposes an ...api package")
  void everyLibraryModuleHasAnApiPackage() {
    for (String module : LIBRARY_MODULES) {
      String apiPackage = "io.cassyx." + module + ".api";
      boolean present =
          PRODUCTION_CLASSES.stream()
              .anyMatch(clazz -> clazz.getPackageName().startsWith(apiPackage));
      org.assertj.core.api.Assertions.assertThat(present)
          .as("cassyx-%s must publish a public %s package", module, apiPackage)
          .isTrue();
    }
  }

  @Test
  @DisplayName("Value objects crossing module boundaries are immutable records")
  void apiPackagesExposeRecords() {
    // Not a hard rule for interfaces and factories, but every non-interface, non-factory,
    // non-exception, non-enum type in an ...api package must be a record (plan section 2.1:
    // "all cross-module communication is via the module's interface or an immutable value object").
    List<String> offenders =
        PRODUCTION_CLASSES.stream()
            .filter(c -> c.getPackageName().matches("io\\.cassyx\\.(?!api)[a-z]+\\.api(\\..*)?"))
            .filter(c -> !c.isInterface() && !c.isEnum() && !c.isRecord())
            .filter(c -> !c.getSimpleName().endsWith("Factory"))
            .filter(c -> !c.getSimpleName().endsWith("Exception"))
            .filter(c -> !c.getSimpleName().endsWith("Selection"))
            .filter(c -> !c.getSimpleName().equals("Secret"))
            .filter(c -> !c.getName().contains("$"))
            .map(c -> c.getName())
            .toList();

    org.assertj.core.api.Assertions.assertThat(offenders)
        .as("api-package value objects must be records (or an explicitly allowed exception)")
        .isEmpty();
  }
}
