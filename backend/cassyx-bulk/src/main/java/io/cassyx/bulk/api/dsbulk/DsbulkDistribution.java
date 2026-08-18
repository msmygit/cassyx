package io.cassyx.bulk.api.dsbulk;

import java.nio.file.Path;
import java.util.List;

/**
 * The DSBulk BINARY DISTRIBUTION shipped inside the Docker image (plan section 5.3).
 *
 * <p>Deliberately the distribution (tar.gz/zip), not the single executable jar: upstream marks the
 * executable jar as evaluation-only, so shipping it in a product is not a licensing position we
 * want to be in.
 *
 * <p>The distribution is never on cassyx's own classpath. DSBulk bundles an {@code application.conf}
 * which Typesafe Config merges by classpath order, so putting it beside Spring Boot's configuration
 * makes both sides read the other's settings. Running the child process against
 * {@code $DSBULK_HOME/lib/*} keeps the two configuration universes physically separate - and buys
 * job isolation, real cancellation, memory capping and immunity to DSBulk's {@code System.exit()}
 * for free.
 */
public interface DsbulkDistribution {

  /** Workflow modules DSBulk discovers through ServiceLoader; all three must be present. */
  List<String> REQUIRED_WORKFLOWS = List.of("dsbulk-workflow-load", "dsbulk-workflow-unload", "dsbulk-workflow-count");

  /** Environment variable naming the unpacked distribution root. */
  String HOME_ENV = "DSBULK_HOME";

  /** Root of the unpacked distribution: contains {@code bin/}, {@code lib/} and {@code conf/}. */
  Path home();

  /** {@code bin/dsbulk} - present in the distribution, absent from the executable jar. */
  Path launcher();

  /** Jar file names found under {@code lib/}. */
  List<String> jars();

  /** {@code true} when every entry of {@link #REQUIRED_WORKFLOWS} has a jar under {@code lib/}. */
  boolean isComplete();

  /** Workflow modules present in this distribution. */
  List<String> workflows();

  /**
   * Throws {@link DsbulkException} with an actionable message unless the distribution is usable.
   *
   * <p>A missing workflow jar does not fail at start-up, it fails at job time with DSBulk's own
   * opaque "first argument must be a subcommand" - so cassyx checks eagerly and says which jar is
   * missing.
   */
  void verify();
}
