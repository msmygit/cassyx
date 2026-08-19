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
    // What DSBulk is actually given: s3.* region/profile/credentials folded into the s3:// URL as
    // query parameters, because they are not settings in 1.11. `masked` keeps them as fields so the
    // UI can still render and edit them.
    List<DsbulkSetting> rendered = DsbulkS3Url.fold(masked);
    List<String> argv = DsbulkCommandBuilder.argv(spec.operation(), rendered, confFile);

    List<String> warnings = new ArrayList<>(warnings(spec, facts));
    warnings.addAll(DsbulkS3Url.warnings(masked));

    return new DsbulkPlan(
        spec.operation(),
        List.copyOf(masked),
        argv,
        DsbulkCommandBuilder.command(argv),
        DsbulkHocon.render(rendered, Map.of()),
        List.copyOf(maskedFields),
        List.copyOf(warnings));
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
    // Secrets are substituted FIRST, then folded into the s3:// URL. The other order would fold the
    // mask into the URL and then have nothing left to substitute it into: the s3 credentials are not
    // settings DSBulk reads (see DsbulkS3Url), so there is no `dsbulk.s3.secretAccessKey` line for a
    // later pass to fix up. The job would authenticate as `***`.
    return DsbulkHocon.render(DsbulkS3Url.fold(substitute(plan.settings(), normalised)), Map.of());
  }

  /** The settings with masked values replaced by their real ones, keyed by setting path. */
  private static List<DsbulkSetting> substitute(
      List<DsbulkSetting> settings, Map<String, String> secrets) {
    if (secrets.isEmpty()) {
      return settings;
    }
    List<DsbulkSetting> out = new ArrayList<>(settings.size());
    for (DsbulkSetting setting : settings) {
      String real = secrets.get(DsbulkReference.normalise(setting.path()));
      out.add(real == null ? setting
          : new DsbulkSetting(setting.path(), real, setting.auto(), setting.upstreamDefault(),
              setting.rationale(), setting.docsUrl(), setting.group()));
    }
    return out;
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
    if (spec.operation() == DsbulkOperation.COUNT) {
      // Cost visibility (plan section 5.4). A count is not a metadata lookup: there is no stored
      // row count in Cassandra, so every row of the table is read off disk on every replica set.
      // Saying so before the job starts is the difference between an informed click and an
      // accidental cluster-wide scan.
      warnings.add(countCost(spec, probe));
    }
    if (spec.operation() == DsbulkOperation.COUNT
        && DsbulkDefaults.normaliseStatsModes(spec.statsModes()).contains("partitions")
        && !probe.hasClusteringKey()) {
      warnings.add("The 'partitions' statistics mode needs a clustering column: DSBulk counts rows "
          + "per partition with a GROUP BY over the partition key, which on a table whose partition "
          + "IS the row can only ever report 1. This table has no clustering column, so the mode is "
          + "refused rather than run to produce a table of ones.");
    }
    return List.copyOf(warnings);
  }

  /** The sentence that appears next to the Recalculate button, with numbers where we have them. */
  static String countCost(DsbulkJobSpec spec, DsbulkProbe probe) {
    StringBuilder text = new StringBuilder("A count reads EVERY row of ")
        .append(spec.qualifiedName())
        .append(" - Cassandra stores no row count, so there is no cheap answer. ");
    if (probe.estimatedRows() != null) {
      text.append("The last count saw roughly ").append(probe.estimatedRows()).append(" rows. ");
    }
    text.append("Expect a full scan across ")
        .append(probe.nodeCount())
        .append(probe.nodeCount() == 1 ? " node" : " nodes")
        .append(" at LOCAL_ONE, competing with production reads for the same page cache.");
    return text.toString();
  }
}
