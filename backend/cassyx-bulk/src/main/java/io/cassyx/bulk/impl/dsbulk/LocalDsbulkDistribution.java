package io.cassyx.bulk.impl.dsbulk;

import io.cassyx.bulk.api.dsbulk.DsbulkDistribution;
import io.cassyx.bulk.api.dsbulk.DsbulkException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * The unpacked DSBulk distribution on the local filesystem (plan section 5.3).
 *
 * <p>Located by {@code DSBULK_HOME}, which the Docker image sets to the directory the distribution
 * tar.gz was extracted into. Nothing here is ever added to cassyx's own classpath.
 */
public final class LocalDsbulkDistribution implements DsbulkDistribution {

  private final Path home;

  public LocalDsbulkDistribution(Path home) {
    this.home = home == null ? null : home.toAbsolutePath().normalize();
  }

  /**
   * Resolves {@code DSBULK_HOME} from the environment.
   *
   * <p>Returns a distribution pointing at a non-existent path rather than throwing when the variable
   * is unset: the application must still boot on a developer machine without DSBulk installed, and
   * report the problem when a DSBulk job is actually requested. A missing bulk loader is not a
   * reason to refuse to serve the schema browser.
   */
  public static LocalDsbulkDistribution fromEnvironment() {
    String configured = System.getenv(HOME_ENV);
    if (configured == null || configured.isBlank()) {
      configured = System.getProperty("cassyx.dsbulk.home", "");
    }
    return new LocalDsbulkDistribution(configured.isBlank() ? Path.of("/opt/dsbulk") : Path.of(configured));
  }

  @Override
  public Path home() {
    return home;
  }

  @Override
  public Path launcher() {
    return home == null ? null : home.resolve("bin").resolve("dsbulk");
  }

  /** {@code lib/} of the distribution: the classpath the child process runs against. */
  public Path libraryDirectory() {
    return home == null ? null : home.resolve("lib");
  }

  @Override
  public List<String> jars() {
    Path lib = libraryDirectory();
    if (lib == null || !Files.isDirectory(lib)) {
      return List.of();
    }
    try (Stream<Path> files = Files.list(lib)) {
      return files
          .map(path -> path.getFileName().toString())
          .filter(name -> name.endsWith(".jar"))
          .sorted()
          .toList();
    } catch (IOException e) {
      throw new DsbulkException("Cannot list the DSBulk library directory at " + lib, e);
    }
  }

  @Override
  public List<String> workflows() {
    List<String> found = new ArrayList<>();
    List<String> jars = jars();
    for (String workflow : REQUIRED_WORKFLOWS) {
      for (String jar : jars) {
        if (jar.toLowerCase(Locale.ROOT).startsWith(workflow)) {
          found.add(workflow);
          break;
        }
      }
    }
    return List.copyOf(found);
  }

  @Override
  public boolean isComplete() {
    return home != null && Files.isDirectory(home) && workflows().size() == REQUIRED_WORKFLOWS.size();
  }

  @Override
  public void verify() {
    if (home == null || !Files.isDirectory(home)) {
      throw new DsbulkException(
          "No DSBulk distribution at " + home + ". Set " + HOME_ENV + " to the directory the "
              + "dsbulk-<version>.tar.gz was extracted into. The Docker image ships one at /opt/dsbulk.");
    }
    Path lib = libraryDirectory();
    if (!Files.isDirectory(lib)) {
      throw new DsbulkException(
          "DSBULK_HOME=" + home + " has no lib/ directory. This looks like the single executable jar "
              + "rather than the binary distribution - cassyx ships the distribution deliberately, "
              + "because upstream marks the executable jar as evaluation-only.");
    }
    List<String> present = workflows();
    if (present.size() != REQUIRED_WORKFLOWS.size()) {
      List<String> missing = new ArrayList<>(REQUIRED_WORKFLOWS);
      missing.removeAll(present);
      throw new DsbulkException(
          "DSBulk distribution at " + home + " is missing workflow module(s) " + missing
              + ". Workflows resolve through ServiceLoader, so a missing jar fails at job time with "
              + "an unhelpful error instead of here.");
    }
  }

  @Override
  public String toString() {
    return "LocalDsbulkDistribution[" + home + ", workflows=" + workflows() + "]";
  }
}
