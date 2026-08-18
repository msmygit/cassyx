package io.cassyx.bulk.api.dsbulk;

import com.datastax.oss.driver.api.core.CqlSession;
import io.cassyx.bulk.impl.dsbulk.DsbulkClusterProber;
import io.cassyx.bulk.impl.dsbulk.DsbulkCountParser;
import io.cassyx.bulk.impl.dsbulk.DsbulkDefaults;
import io.cassyx.bulk.impl.dsbulk.DsbulkPlanner;
import io.cassyx.bulk.impl.dsbulk.DsbulkReference;
import io.cassyx.bulk.impl.dsbulk.LocalDsbulkDistribution;
import io.cassyx.bulk.impl.dsbulk.ProcessDsbulkRunner;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The single composition entry point of the DSBulk integration - the only class outside
 * {@code io.cassyx.bulk.impl.dsbulk} that knows the implementations exist (plan section 2.1).
 *
 * <p>Usable with nothing but a {@code CqlSession}, no Spring anywhere in sight:
 *
 * <pre>{@code
 * DsbulkJobSpec spec = DsbulkJobSpec.table(DsbulkOperation.UNLOAD, "demo", "users", "csv", "/out");
 * DsbulkProbe probe = DsbulkFactory.probe(session, "demo", "users");
 * DsbulkPlan plan = DsbulkFactory.plan(spec, probe, Path.of("/jobs/42"), "UNLOAD_42");
 *
 * System.out.println(plan.command());   // the exact, copyable dsbulk invocation
 * System.out.println(plan.hocon());     // the generated, reproducible configuration
 *
 * DsbulkResult result =
 *     DsbulkFactory.runner().run(plan, Path.of("/jobs/42"), Map.of(), DsbulkListener.noop());
 * }</pre>
 */
public final class DsbulkFactory {

  private DsbulkFactory() {}

  /** The DSBulk distribution named by {@code DSBULK_HOME}. */
  public static DsbulkDistribution distribution() {
    return LocalDsbulkDistribution.fromEnvironment();
  }

  public static DsbulkDistribution distribution(Path home) {
    return new LocalDsbulkDistribution(home);
  }

  /** Out-of-process runner over the environment's distribution, capped at {@code maxHeap}. */
  public static DsbulkRunner runner(String maxHeap) {
    return new ProcessDsbulkRunner(distribution(), maxHeap);
  }

  public static DsbulkRunner runner(DsbulkDistribution distribution, String maxHeap) {
    return new ProcessDsbulkRunner(distribution, maxHeap);
  }

  /** Probes the cluster for the facts every derivation is computed from. */
  public static DsbulkProbe probe(CqlSession session, String keyspace, String table) {
    return DsbulkClusterProber.probe(session, keyspace, table);
  }

  /** The derived-defaults table of plan section 5.3, as an explainable list of settings. */
  public static List<DsbulkSetting> derive(DsbulkJobSpec spec, DsbulkProbe probe) {
    return DsbulkDefaults.derive(spec, probe);
  }

  /** Plans a job: derived settings plus the argv, the command line and the generated HOCON. */
  public static DsbulkPlan plan(DsbulkJobSpec spec, DsbulkProbe probe, Path jobDirectory, String executionId) {
    return DsbulkPlanner.plan(spec, probe, jobDirectory, executionId);
  }

  /** The configuration file contents with secrets restored - never returned over the API. */
  public static String configurationFile(DsbulkPlan plan, Map<String, String> secrets) {
    return DsbulkPlanner.realHocon(plan, secrets);
  }

  /** DSBulk's own default for a setting path, for rendering as placeholder text. */
  public static String upstreamDefault(String path) {
    return DsbulkReference.upstreamDefault(path);
  }

  /** Deep link into the upstream settings reference for a setting path. */
  public static String docsUrl(String path) {
    return DsbulkReference.docsUrl(path);
  }

  /** True when a setting's value is a credential and must never be echoed back. */
  public static boolean isSecret(String path) {
    return DsbulkReference.isSecret(path);
  }

  /** Null tokens sniffed from a sample of the source file, for {@code codec.nullStrings}. */
  public static List<String> sniffNullStrings(List<String> sampleValues) {
    return DsbulkDefaults.sniffNullStrings(sampleValues);
  }

  /** Parses the {@code count} workflow's output into the Statistics tab's model. */
  public static DsbulkCountReport parseCountOutput(List<String> lines) {
    return DsbulkCountParser.parse(lines);
  }
}
