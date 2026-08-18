package io.cassyx.bulk.impl.dsbulk;

import io.cassyx.bulk.api.dsbulk.DsbulkSettingGroup;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The upstream DSBulk settings reference, in one place.
 *
 * <p>Two jobs, both required by plan section 5.3:
 *
 * <ol>
 *   <li><b>Upstream defaults.</b> Every field in the Advanced accordion renders DSBulk's own default
 *       as placeholder text, so a user can always see what happens if they leave it alone. That is
 *       what makes "expose the full settings surface" usable rather than a wall of empty inputs.
 *   <li><b>Documentation links.</b> Every field links to the upstream settings reference for that
 *       exact option, because no tooltip we write will beat the real docs.
 * </ol>
 *
 * <p>Paths are the DSBulk-relative form ({@code connector.csv.delimiter}), NOT the
 * {@code dsbulk.}-prefixed form. {@link DsbulkHocon} adds the namespace when rendering, and
 * {@link DsbulkCommandBuilder} adds the {@code --} when building argv. Driver settings are the one
 * exception: DSBulk 1.x expresses them in the driver's own {@code datastax-java-driver} namespace,
 * so they are stored here under the contract's friendly {@code driver.*} name and translated by
 * {@link #toDsbulkPath(String)}.
 */
public final class DsbulkReference {

  /** Anchor base for the upstream settings reference. */
  public static final String DOCS_BASE = "https://docs.datastax.com/en/dsbulk/docs/reference/settings.html";

  /** Root namespace of DSBulk's own settings inside the generated HOCON. */
  public static final String DSBULK_NAMESPACE = "dsbulk";

  /** Root namespace of driver settings: DSBulk 1.x delegates these to the driver's own config. */
  public static final String DRIVER_NAMESPACE = "datastax-java-driver";

  /**
   * Contract-friendly {@code driver.*} paths to their real driver-config paths.
   *
   * <p>The API contract models these as {@code driver.basic.requestConsistency} (camelCase, nested
   * under {@code driver}) because that is what reads well in a JSON body and in the UI. DSBulk
   * wants {@code datastax-java-driver.basic.request.consistency}. Getting this translation wrong is
   * the single easiest way to generate a command that looks right and silently ignores the setting,
   * because Typesafe Config does not reject unknown keys - hence the explicit table.
   */
  private static final Map<String, String> DRIVER_PATHS = driverPaths();

  /** Settings whose values must never appear in a preview, a log line or an error message. */
  private static final Set<String> SECRET_PATHS =
      Set.of(
          "s3.accessKeyId",
          "s3.secretAccessKey",
          "s3.sessionToken",
          "driver.advanced.authProvider.password",
          "driver.advanced.auth-provider.password",
          "datastax-java-driver.advanced.auth-provider.password");

  private static final Map<String, String> DEFAULTS = defaults();

  /**
   * Contract spelling to the path DSBulk 1.11 actually defines.
   *
   * <p>The API contract flattens three log options that upstream nests one level deeper. Typesafe
   * Config does not reject unknown keys, so the flat spellings would be accepted silently and do
   * nothing at all - the worst kind of wrong, because the preview shows the setting and the job
   * ignores it. Aliasing at normalisation time means every downstream consumer - defaults, docs
   * link, group, HOCON, argv - sees the real path.
   */
  private static final Map<String, String> ALIASES =
      Map.of(
          "log.maxQueryStringLength", "log.stmt.maxQueryStringLength",
          "log.maxBoundValueLength", "log.stmt.maxBoundValueLength",
          "log.maxResultSetValueLength", "log.row.maxResultSetValueLength");

  /**
   * {@code log.verbosity} is an ENUM upstream ({@code quiet|normal|high|max}) but an integer 0..2 in
   * the contract. Sending "1" would be rejected at start-up by DSBulk's own config validation.
   */
  private static final Map<String, String> VERBOSITY =
      Map.of("0", "quiet", "1", "normal", "2", "high", "3", "max");

  private DsbulkReference() {}

  /** DSBulk's own default for {@code path}, or {@code null} when the option has none. */
  public static String upstreamDefault(String path) {
    return DEFAULTS.get(normalise(path));
  }

  /** Deep link to the upstream documentation anchor for {@code path}. */
  public static String docsUrl(String path) {
    return DOCS_BASE + "#" + normalise(path);
  }

  public static DsbulkSettingGroup group(String path) {
    return DsbulkSettingGroup.of(normalise(path));
  }

  /** True when the value of {@code path} is a credential and must be masked. */
  public static boolean isSecret(String path) {
    String normalised = normalise(path);
    return SECRET_PATHS.contains(normalised)
        || normalised.toLowerCase(Locale.ROOT).contains("password")
        || normalised.toLowerCase(Locale.ROOT).contains("secretaccesskey")
        || normalised.toLowerCase(Locale.ROOT).contains("sessiontoken");
  }

  /** Every setting path cassyx models explicitly. */
  public static Set<String> knownPaths() {
    return DEFAULTS.keySet();
  }

  /** Strips a leading {@code dsbulk.} so callers may pass either spelling. */
  public static String normalise(String path) {
    if (path == null) {
      return "";
    }
    String trimmed = path.trim();
    String stripped =
        trimmed.startsWith(DSBULK_NAMESPACE + ".")
            ? trimmed.substring(DSBULK_NAMESPACE.length() + 1)
            : trimmed;
    return ALIASES.getOrDefault(stripped, stripped);
  }

  /**
   * Coerces a contract-shaped value into the literal DSBulk expects.
   *
   * <p>Only {@code log.verbosity} needs it today, but the seam is where any future contract/upstream
   * type mismatch belongs - the alternative is a silent config rejection at job start.
   */
  public static String translateValue(String path, String value) {
    if (value == null) {
      return null;
    }
    if ("log.verbosity".equals(normalise(path))) {
      return VERBOSITY.getOrDefault(value.trim(), value);
    }
    return value;
  }

  /**
   * The fully-qualified path DSBulk actually reads.
   *
   * <p>{@code batch.mode} becomes {@code dsbulk.batch.mode}; {@code driver.basic.requestConsistency}
   * becomes {@code datastax-java-driver.basic.request.consistency}.
   */
  public static String toDsbulkPath(String path) {
    String normalised = normalise(path);
    if (normalised.startsWith(DRIVER_NAMESPACE + ".")) {
      return normalised;
    }
    String mapped = DRIVER_PATHS.get(normalised);
    if (mapped != null) {
      return mapped;
    }
    if (normalised.startsWith("driver.")) {
      // An unmodelled driver.* option: pass it straight through to the driver namespace, converting
      // the contract's camelCase tail to the driver's kebab-case.
      return DRIVER_NAMESPACE + "." + toKebab(normalised.substring("driver.".length()));
    }
    return DSBULK_NAMESPACE + "." + normalised;
  }

  /** {@code requestConsistency} to {@code request-consistency}; segment-wise, dots preserved. */
  static String toKebab(String camel) {
    StringBuilder out = new StringBuilder(camel.length() + 4);
    for (int i = 0; i < camel.length(); i++) {
      char c = camel.charAt(i);
      if (Character.isUpperCase(c)) {
        out.append('-').append(Character.toLowerCase(c));
      } else {
        out.append(c);
      }
    }
    return out.toString();
  }

  private static Map<String, String> driverPaths() {
    Map<String, String> m = new LinkedHashMap<>();
    m.put("driver.basic.requestConsistency", DRIVER_NAMESPACE + ".basic.request.consistency");
    m.put("driver.basic.requestSerialConsistency", DRIVER_NAMESPACE + ".basic.request.serial-consistency");
    m.put("driver.basic.requestTimeout", DRIVER_NAMESPACE + ".basic.request.timeout");
    m.put("driver.basic.requestPageSize", DRIVER_NAMESPACE + ".basic.request.page-size");
    m.put("driver.basic.requestDefaultIdempotence", DRIVER_NAMESPACE + ".basic.request.default-idempotence");
    m.put("driver.basic.sessionName", DRIVER_NAMESPACE + ".basic.session-name");
    m.put("driver.basic.contactPoints", DRIVER_NAMESPACE + ".basic.contact-points");
    m.put("driver.basic.loadBalancingPolicy.localDatacenter",
        DRIVER_NAMESPACE + ".basic.load-balancing-policy.local-datacenter");
    m.put("driver.basic.cloud.secureConnectBundle", DRIVER_NAMESPACE + ".basic.cloud.secure-connect-bundle");
    m.put("driver.advanced.protocolCompression", DRIVER_NAMESPACE + ".advanced.protocol.compression");
    m.put("driver.advanced.connectionPoolLocalSize", DRIVER_NAMESPACE + ".advanced.connection.pool.local.size");
    m.put("driver.advanced.connectionPoolRemoteSize", DRIVER_NAMESPACE + ".advanced.connection.pool.remote.size");
    m.put("driver.advanced.retryPolicyClass", DRIVER_NAMESPACE + ".advanced.retry-policy.class");
    m.put("driver.advanced.maxRetries", DRIVER_NAMESPACE + ".advanced.retry-policy.max-retries");
    m.put("driver.advanced.heartbeatInterval", DRIVER_NAMESPACE + ".advanced.heartbeat.interval");
    m.put("driver.advanced.metadataSchemaEnabled", DRIVER_NAMESPACE + ".advanced.metadata.schema.enabled");
    m.put("driver.advanced.authProvider.class", DRIVER_NAMESPACE + ".advanced.auth-provider.class");
    m.put("driver.advanced.authProvider.username", DRIVER_NAMESPACE + ".advanced.auth-provider.username");
    m.put("driver.advanced.authProvider.password", DRIVER_NAMESPACE + ".advanced.auth-provider.password");
    return Map.copyOf(m);
  }

  private static Map<String, String> defaults() {
    Map<String, String> m = new LinkedHashMap<>();

    // ---- connector ------------------------------------------------------------------------
    m.put("connector.name", "csv");
    m.put("connector.csv.url", "-");
    m.put("connector.csv.urlfile", "");
    m.put("connector.csv.fileNamePattern", "**/*.csv");
    m.put("connector.csv.fileNameFormat", "output-%06d.csv");
    m.put("connector.csv.recursive", "false");
    m.put("connector.csv.header", "true");
    m.put("connector.csv.delimiter", ",");
    m.put("connector.csv.quote", "\"");
    m.put("connector.csv.escape", "\\");
    m.put("connector.csv.comment", "\\u0000");
    m.put("connector.csv.newline", "auto");
    m.put("connector.csv.encoding", "UTF-8");
    m.put("connector.csv.skipRecords", "0");
    m.put("connector.csv.maxRecords", "-1");
    m.put("connector.csv.maxConcurrentFiles", "AUTO");
    m.put("connector.csv.maxCharsPerColumn", "4096");
    m.put("connector.csv.maxColumns", "512");
    m.put("connector.csv.ignoreLeadingWhitespaces", "false");
    m.put("connector.csv.ignoreTrailingWhitespaces", "false");
    m.put("connector.csv.nullValue", "AUTO");
    m.put("connector.csv.emptyValue", "AUTO");
    m.put("connector.csv.normalizeLineEndingsInQuotes", "false");
    m.put("connector.csv.compression", "none");
    m.put("connector.json.url", "-");
    m.put("connector.json.urlfile", "");
    m.put("connector.json.fileNamePattern", "**/*.json");
    m.put("connector.json.fileNameFormat", "output-%06d.json");
    m.put("connector.json.mode", "MULTI_DOCUMENT");
    m.put("connector.json.recursive", "false");
    m.put("connector.json.encoding", "UTF-8");
    m.put("connector.json.skipRecords", "0");
    m.put("connector.json.maxRecords", "-1");
    m.put("connector.json.maxConcurrentFiles", "AUTO");
    m.put("connector.json.prettyPrint", "false");
    m.put("connector.json.compression", "none");

    // ---- schema ---------------------------------------------------------------------------
    m.put("schema.keyspace", null);
    m.put("schema.table", null);
    m.put("schema.query", null);
    m.put("schema.mapping", null);
    m.put("schema.nullToUnset", "true");
    m.put("schema.allowExtraFields", "true");
    m.put("schema.allowMissingFields", "false");
    m.put("schema.splits", "8C");
    m.put("schema.queryTimestamp", null);
    m.put("schema.queryTtl", "-1");
    m.put("schema.preserveTimestamp", "false");
    m.put("schema.preserveTtl", "false");

    // ---- batch ----------------------------------------------------------------------------
    m.put("batch.mode", "PARTITION_KEY");
    m.put("batch.maxBatchStatements", "32");
    m.put("batch.maxSizeInBytes", "-1");
    m.put("batch.bufferSize", "-1");

    // ---- codec ----------------------------------------------------------------------------
    m.put("codec.locale", "en_US");
    m.put("codec.timeZone", "UTC");
    m.put("codec.booleanStrings", "[\"1:0\",\"Y:N\",\"T:F\",\"YES:NO\",\"TRUE:FALSE\"]");
    m.put("codec.booleanNumbers", "[1, 0]");
    m.put("codec.number", "#,###.##");
    m.put("codec.formatNumbers", "false");
    m.put("codec.roundingStrategy", "UNNECESSARY");
    m.put("codec.overflowStrategy", "REJECT");
    m.put("codec.timestamp", "CQL_TIMESTAMP");
    m.put("codec.date", "ISO_LOCAL_DATE");
    m.put("codec.time", "ISO_LOCAL_TIME");
    m.put("codec.unit", "MILLISECONDS");
    m.put("codec.epoch", "1970-01-01T00:00:00Z");
    m.put("codec.nullStrings", "[]");
    m.put("codec.binary", "BASE64");
    m.put("codec.uuidStrategy", "RANDOM");

    // ---- engine ---------------------------------------------------------------------------
    m.put("engine.dryRun", "false");
    m.put("engine.executionId", null);
    m.put("engine.maxConcurrentQueries", "AUTO");
    m.put("engine.dataSizeSamplingEnabled", "true");

    // ---- executor -------------------------------------------------------------------------
    m.put("executor.maxPerSecond", "-1");
    m.put("executor.maxInFlight", "-1");
    m.put("executor.maxBytesPerSecond", "-1");
    m.put("executor.continuousPaging.enabled", "true");
    m.put("executor.continuousPaging.pageSize", "5000");
    m.put("executor.continuousPaging.pageUnit", "ROWS");
    m.put("executor.continuousPaging.maxPages", "0");
    m.put("executor.continuousPaging.maxPagesPerSecond", "0");

    // ---- log ------------------------------------------------------------------------------
    m.put("log.directory", "./logs");
    m.put("log.verbosity", "normal");
    m.put("log.maxErrors", "100");
    m.put("log.stmt.maxQueryStringLength", "500");
    m.put("log.stmt.maxBoundValueLength", "50");
    m.put("log.row.maxResultSetValueLength", "50");
    m.put("log.ansiMode", "normal");

    // ---- monitoring -----------------------------------------------------------------------
    m.put("monitoring.reportRate", "5 seconds");
    m.put("monitoring.rateUnit", "SECONDS");
    m.put("monitoring.durationUnit", "MILLISECONDS");
    m.put("monitoring.expectedWrites", "-1");
    m.put("monitoring.expectedReads", "-1");
    m.put("monitoring.trackBytes", "false");
    m.put("monitoring.jmx", "true");
    m.put("monitoring.csv", "false");
    m.put("monitoring.console", "true");

    // ---- driver ---------------------------------------------------------------------------
    m.put("driver.basic.requestConsistency", "LOCAL_ONE");
    m.put("driver.basic.requestSerialConsistency", "LOCAL_SERIAL");
    m.put("driver.basic.requestTimeout", "5 minutes");
    m.put("driver.basic.requestPageSize", "5000");
    m.put("driver.basic.requestDefaultIdempotence", "true");
    m.put("driver.basic.sessionName", null);
    m.put("driver.advanced.protocolCompression", "none");
    m.put("driver.advanced.connectionPoolLocalSize", "8");
    m.put("driver.advanced.connectionPoolRemoteSize", "8");
    m.put("driver.advanced.retryPolicyClass",
        "com.datastax.oss.dsbulk.workflow.commons.policies.retry.MultipleRetryPolicy");
    m.put("driver.advanced.maxRetries", "10");
    m.put("driver.advanced.heartbeatInterval", "30 seconds");
    m.put("driver.advanced.metadataSchemaEnabled", "true");

    // ---- s3 -------------------------------------------------------------------------------
    m.put("s3.clientCacheSize", "20");
    // The rest of the contract's s3.* group is NOT a DSBulk setting group in 1.11: region, profile
    // and the credentials are QUERY PARAMETERS on the s3:// URL, and sessionToken/endpoint do not
    // exist at all. DsbulkS3Url does that translation; these entries exist so the UI can still
    // render and document the fields.
    m.put("s3.region", null);
    m.put("s3.profile", null);
    m.put("s3.accessKeyId", null);
    m.put("s3.secretAccessKey", null);
    m.put("s3.sessionToken", null);
    m.put("s3.endpoint", null);

    // ---- stats ----------------------------------------------------------------------------
    m.put("stats.modes", "[global]");
    m.put("stats.numPartitions", "10");

    Map<String, String> copy = new LinkedHashMap<>();
    m.forEach(copy::put);
    return Collections.unmodifiableMap(copy);
  }
}
