package io.cassyx.bulk.impl.dsbulk;

import io.cassyx.bulk.api.dsbulk.DsbulkJobSpec;
import io.cassyx.bulk.api.dsbulk.DsbulkOperation;
import io.cassyx.bulk.api.dsbulk.DsbulkPlan;
import io.cassyx.bulk.api.dsbulk.DsbulkProbe;
import io.cassyx.bulk.api.dsbulk.DsbulkSetting;
import io.cassyx.core.api.ClusterFlavor;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns a {@link DsbulkJobSpec} plus a {@link DsbulkProbe} into a runnable, previewable
 * {@link DsbulkPlan}.
 *
 * <p>One planner serves both {@code POST /bulk/command-preview} and the job runner, which is the
 * property that makes the preview trustworthy: the command shown is the command run, not a
 * best-effort reconstruction of it.
 *
 * <p>The plan it returns is <b>always safe to return over the API and to persist</b> - secret values
 * are replaced by {@value DsbulkCommandBuilder#MASK} before they get anywhere near it. Real
 * credentials travel separately, straight into the file the runner writes.
 */
public final class DsbulkPlanner {

  /** DSBulk's per-job log directory, inside the job directory so it is reaped with the job. */
  public static final String LOG_DIR_NAME = "logs";

  private DsbulkPlanner() {}

  /**
   * @param jobDirectory the job's temp dir; receives {@code dsbulk.conf} and {@code logs/}
   * @param executionId DSBulk execution id; also names the log subdirectory
   */
  public static DsbulkPlan plan(
      DsbulkJobSpec spec, DsbulkProbe probe, Path jobDirectory, String executionId) {
    DsbulkProbe facts = probe == null ? DsbulkProbe.UNKNOWN : probe;
    List<DsbulkSetting> derived = new ArrayList<>(DsbulkDefaults.derive(spec, facts));
    Map<String, DsbulkSetting> byPath = new LinkedHashMap<>();
    derived.forEach(setting -> byPath.put(setting.path(), setting));

    if (jobDirectory != null) {
      byPath.put("log.directory", DsbulkSetting.derived("log.directory",
          jobDirectory.resolve(LOG_DIR_NAME).toString(),
          DsbulkReference.upstreamDefault("log.directory"),
          "Per-job log directory inside the job's temp dir. cassyx tails it for progress and "
              + "retains it as the downloadable job log.",
          DsbulkReference.docsUrl("log.directory")));
    }
    if (executionId != null && !executionId.isBlank()) {
      byPath.put("engine.executionId", DsbulkSetting.derived("engine.executionId", executionId,
          null,
          "Derived from the cassyx job id so the DSBulk log subdirectory, the operation id in the "
              + "logs and the job row all name the same thing.",
          DsbulkReference.docsUrl("engine.executionId")));
    }

    List<DsbulkSetting> masked = new ArrayList<>(byPath.size());
    List<String> maskedFields = new ArrayList<>();
    for (DsbulkSetting setting : byPath.values()) {
      if (DsbulkReference.isSecret(setting.path())) {
        maskedFields.add(setting.path());
        masked.add(new DsbulkSetting(setting.path(), DsbulkCommandBuilder.MASK, setting.auto(),
            setting.upstreamDefault(), setting.rationale(), setting.docsUrl(), setting.group()));
      } else {
        masked.add(setting);
      }
    }

    String confFile = jobDirectory == null
        ? DsbulkCommandBuilder.CONF_FILE_NAME
        : jobDirectory.resolve(DsbulkCommandBuilder.CONF_FILE_NAME).toString();
    List<String> argv = DsbulkCommandBuilder.argv(spec.operation(), masked, confFile);

    return new DsbulkPlan(
        spec.operation(),
        List.copyOf(masked),
        argv,
        DsbulkCommandBuilder.command(argv),
        DsbulkHocon.render(masked, Map.of()),
        List.copyOf(maskedFields),
        warnings(spec, facts));
  }

  /**
   * The real configuration file contents: the plan's settings with secrets restored.
   *
   * @param secrets setting path to real value; keys may carry the {@code dsbulk.} prefix or not
   */
  public static String realHocon(DsbulkPlan plan, Map<String, String> secrets) {
    Map<String, String> normalised = new LinkedHashMap<>();
    if (secrets != null) {
      secrets.forEach((key, value) -> normalised.put(DsbulkReference.normalise(key), value));
    }
    return DsbulkHocon.render(plan.settings(), normalised);
  }

  /** Honest, actionable warnings, surfaced next to the preview rather than discovered at run time. */
  static List<String> warnings(DsbulkJobSpec spec, DsbulkProbe probe) {
    List<String> warnings = new ArrayList<>();
    if (!probe.supportsTokenRangeScan() && spec.operation() != DsbulkOperation.LOAD) {
      warnings.add("This target does not support token() range scans, so schema.splits has no effect "
          + "and the workflow falls back to plain paging. Expect substantially lower throughput.");
    }
    if (probe.flavour() == ClusterFlavor.ASTRA && spec.operation() == DsbulkOperation.LOAD) {
      warnings.add("Astra applies server-side rate limiting. DSBulk honours it automatically, so a "
          + "load may run slower than the local throughput numbers suggest - that is the server "
          + "protecting itself, not a misconfiguration.");
    }
    if (probe.counterTable() && spec.operation() == DsbulkOperation.LOAD) {
      warnings.add("Counter table: counter updates are not idempotent, so a retried write can "
          + "double-count. Batches are capped at one statement and retries are the driver's, but a "
          + "failed load cannot simply be re-run from the start.");
    }
    if (spec.operation() == DsbulkOperation.LOAD && (spec.mapping() == null || spec.mapping().isBlank())) {
      warnings.add("No explicit mapping: DSBulk will map by header name. Verify the dry run before "
          + "loading for real.");
    }
    if (spec.operation() == DsbulkOperation.COUNT
        && spec.statsModes().stream().noneMatch(m -> m.toLowerCase(Locale.ROOT).contains("partition"))) {
      warnings.add("No partition mode selected, so the largest-partitions report - the skew signal "
          + "the Statistics tab and export pre-flight both use - will be empty.");
    }
    return List.copyOf(warnings);
  }
}
