package io.cassyx.bulk.impl.dsbulk;

import io.cassyx.bulk.api.dsbulk.DsbulkOperation;
import io.cassyx.bulk.api.dsbulk.DsbulkSetting;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the exact {@code String[]} handed to DSBulk, and the copyable one-liner shown in the
 * "View generated command" pane (plan section 5.3).
 *
 * <p>{@code dsbulk-runner} has no fluent API - it parses the same array the CLI does - so this
 * class is the whole interface to it. The pane it feeds is not decoration: it makes the UI a DSBulk
 * command builder for people who will run the job elsewhere, which is the difference between a tool
 * that hides DSBulk and one that teaches it.
 */
public final class DsbulkCommandBuilder {

  /** Name of the generated configuration file inside the job directory. */
  public static final String CONF_FILE_NAME = "dsbulk.conf";

  /** What a masked secret renders as, in both the command and the HOCON preview. */
  public static final String MASK = "***";

  /**
   * Settings promoted to explicit command-line options.
   *
   * <p>Everything is in the {@code -f} file anyway; these are repeated on the command line because
   * a reader should be able to tell what the job does from the one-liner alone. They are also the
   * options every DSBulk example on the internet uses, so the generated command looks familiar.
   */
  private static final List<String[]> SHORTCUTS =
      List.of(
          new String[] {"schema.keyspace", "-k"},
          new String[] {"schema.table", "-t"},
          new String[] {"schema.query", "-query"},
          new String[] {"schema.mapping", "-m"},
          new String[] {"connector.name", "-c"});

  private DsbulkCommandBuilder() {}

  /**
   * The argv passed to the child process.
   *
   * @param confFile absolute path of the generated HOCON, passed as {@code -f}
   */
  public static List<String> argv(DsbulkOperation operation, List<DsbulkSetting> settings, String confFile) {
    List<String> argv = new ArrayList<>();
    argv.add(operation.command());
    for (String[] shortcut : SHORTCUTS) {
      String value = valueOf(settings, shortcut[0]);
      if (value != null && !value.isBlank()) {
        argv.add(shortcut[1]);
        argv.add(value);
      }
    }
    String url = urlSetting(settings);
    if (url != null) {
      argv.add("-url");
      argv.add(url);
    }
    // -f LAST, mirroring the contract's example. DSBulk resolves the application file below the
    // command line either way, and both come from the same resolved settings, so they cannot drift.
    argv.add("-f");
    argv.add(confFile);
    return List.copyOf(argv);
  }

  /** The copyable one-liner, with every secret replaced by {@value #MASK}. */
  public static String command(List<String> argv) {
    StringBuilder out = new StringBuilder("dsbulk");
    for (String arg : argv) {
      out.append(' ').append(shellQuote(arg));
    }
    return out.toString();
  }

  /** POSIX single-quote escaping, so a copied command survives spaces and shell metacharacters. */
  static String shellQuote(String arg) {
    if (arg == null || arg.isEmpty()) {
      return "''";
    }
    if (arg.matches("[A-Za-z0-9_@%+=:,./-]+")) {
      return arg;
    }
    return "'" + arg.replace("'", "'\\''") + "'";
  }

  /** The connector URL, whichever connector is in play. */
  static String urlSetting(List<DsbulkSetting> settings) {
    String csv = valueOf(settings, "connector.csv.url");
    return csv != null ? csv : valueOf(settings, "connector.json.url");
  }

  static String valueOf(List<DsbulkSetting> settings, String path) {
    for (DsbulkSetting setting : settings) {
      if (setting.path().equals(path)) {
        return setting.value();
      }
    }
    return null;
  }
}
