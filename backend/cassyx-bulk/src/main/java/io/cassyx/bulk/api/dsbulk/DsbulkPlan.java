package io.cassyx.bulk.api.dsbulk;

import java.util.List;
import java.util.Objects;

/**
 * Everything needed to run - and to reproduce - one DSBulk job.
 *
 * <p>This is the API contract's {@code BulkCommandPreview} plus the resolved settings. The same
 * object drives {@code POST /bulk/command-preview} (masked) and the actual child process
 * (unmasked), which is what makes the preview honest: the UI shows the command that will really
 * run, so it doubles as a DSBulk command builder for people running it elsewhere.
 *
 * @param argv the exact {@code String[]} handed to the child process - {@code dsbulk-runner} has no
 *     fluent API, it parses the same array the CLI does
 * @param command the copyable single-line invocation, with secrets replaced by {@code ***}
 * @param hocon the generated per-job HOCON. Embedded DSBulk has no {@code conf/} directory, so
 *     cassyx always writes this file into the job temp dir and passes {@code -f} (plan section 5.3)
 * @param maskedFields setting paths whose values were redacted in {@code command} and {@code hocon}
 */
public record DsbulkPlan(
    DsbulkOperation operation,
    List<DsbulkSetting> settings,
    List<String> argv,
    String command,
    String hocon,
    List<String> maskedFields,
    List<String> warnings) {

  public DsbulkPlan {
    Objects.requireNonNull(operation, "operation");
    settings = settings == null ? List.of() : List.copyOf(settings);
    argv = argv == null ? List.of() : List.copyOf(argv);
    maskedFields = maskedFields == null ? List.of() : List.copyOf(maskedFields);
    warnings = warnings == null ? List.of() : List.copyOf(warnings);
    command = command == null ? "" : command;
    hocon = hocon == null ? "" : hocon;
  }

  /** The resolved value of one setting, or {@code null} when it is not part of this plan. */
  public String value(String path) {
    for (DsbulkSetting setting : settings) {
      if (setting.path().equals(path)) {
        return setting.value();
      }
    }
    return null;
  }

  public List<DsbulkSetting> group(DsbulkSettingGroup group) {
    return settings.stream().filter(s -> s.group() == group).toList();
  }
}
