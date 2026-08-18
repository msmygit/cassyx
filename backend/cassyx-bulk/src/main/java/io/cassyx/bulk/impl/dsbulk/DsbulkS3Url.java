package io.cassyx.bulk.impl.dsbulk;

import io.cassyx.bulk.api.dsbulk.DsbulkSetting;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Translates the contract's {@code s3.*} settings into what DSBulk 1.11 actually accepts.
 *
 * <p><b>{@code s3} is very nearly not a settings group at all.</b> Verified against the shipped
 * distribution, not assumed:
 *
 * <ul>
 *   <li>{@code s3.clientCacheSize} is the ONLY real setting in the group.
 *   <li>{@code region} (mandatory), {@code profile}, {@code accessKeyId} and {@code secretAccessKey}
 *       are <b>query parameters on the {@code s3://} URL</b>, not settings.
 *   <li>{@code sessionToken} and {@code endpoint} do not exist upstream in any form.
 * </ul>
 *
 * <p>Why this class has to exist rather than the settings simply being passed through: Typesafe
 * Config does not reject unknown keys. Emitting {@code dsbulk.s3.region = "eu-west-1"} produces a
 * configuration file DSBulk accepts without complaint and then ignores, so the preview shows the
 * region, the HOCON shows the region, and the job fails to reach the bucket - or worse, reaches the
 * wrong one. Silent acceptance is the reason this is a translation and not a rename.
 *
 * <p>The translation is applied to <b>rendered output</b> ({@code -url} and the HOCON), never to the
 * resolved setting list. The UI still gets its {@code s3.region} / {@code s3.accessKeyId} fields to
 * render and edit, and the existing secret machinery still masks them by path - folding credentials
 * into the URL at derivation time would put a live secret inside a non-secret setting.
 */
public final class DsbulkS3Url {

  /** {@code s3.*} keys that are URL query parameters upstream, in the order DSBulk documents them. */
  static final List<String> URL_PARAMETERS =
      List.of("region", "profile", "accessKeyId", "secretAccessKey");

  /** {@code s3.*} keys the contract models that DSBulk 1.11 does not implement in any form. */
  static final Set<String> UNSUPPORTED = Set.of("sessionToken", "endpoint");

  /** The one genuine member of the {@code s3} settings group. */
  static final String CLIENT_CACHE_SIZE = "s3.clientCacheSize";

  private DsbulkS3Url() {}

  /**
   * Folds the {@code s3.*} settings into the connector URL and drops the ones that are not settings.
   *
   * <p>Called with whatever values are effective at the call site: masked ones for the preview, real
   * ones for the file the runner writes. Both go through the same code, so the previewed URL has the
   * same shape as the one the job runs with.
   *
   * @return the same settings with the connector URL rewritten and the non-settings removed
   */
  public static List<DsbulkSetting> fold(List<DsbulkSetting> settings) {
    if (settings == null || settings.isEmpty()) {
      return List.of();
    }
    Map<String, String> parameters = new LinkedHashMap<>();
    for (String name : URL_PARAMETERS) {
      String value = valueOf(settings, "s3." + name);
      if (value != null && !value.isBlank()) {
        parameters.put(name, value);
      }
    }

    List<DsbulkSetting> out = new ArrayList<>(settings.size());
    for (DsbulkSetting setting : settings) {
      String suffix = s3Suffix(setting.path());
      if (suffix != null && !CLIENT_CACHE_SIZE.equals(setting.path())) {
        // Either a query parameter (now carried by the URL) or a setting that does not exist.
        // Emitting it would be a line DSBulk silently ignores.
        continue;
      }
      if (isConnectorUrl(setting.path()) && isS3(setting.value())) {
        out.add(new DsbulkSetting(setting.path(), withParameters(setting.value(), parameters),
            setting.auto(), setting.upstreamDefault(), setting.rationale(), setting.docsUrl(),
            setting.group()));
      } else {
        out.add(setting);
      }
    }
    return List.copyOf(out);
  }

  /**
   * Appends {@code parameters} to {@code url}, leaving anything the user already spelled out alone.
   *
   * <p>A URL the user typed with its own {@code ?region=...} wins: they were explicit, and silently
   * overwriting it with a value from a form field they may not have noticed is how a job ends up
   * pointed at the wrong region.
   */
  static String withParameters(String url, Map<String, String> parameters) {
    if (parameters.isEmpty()) {
      return url;
    }
    int query = url.indexOf('?');
    String existing = query < 0 ? "" : url.substring(query + 1);
    StringBuilder out = new StringBuilder(url);
    char separator = query < 0 ? '?' : (existing.isEmpty() ? '\0' : '&');
    for (Map.Entry<String, String> parameter : parameters.entrySet()) {
      if (alreadyPresent(existing, parameter.getKey())) {
        continue;
      }
      if (separator != '\0') {
        out.append(separator);
      }
      separator = '&';
      out.append(parameter.getKey())
          .append('=')
          .append(URLEncoder.encode(parameter.getValue(), StandardCharsets.UTF_8));
    }
    return out.toString();
  }

  private static boolean alreadyPresent(String query, String name) {
    for (String pair : query.split("&")) {
      int equals = pair.indexOf('=');
      String key = equals < 0 ? pair : pair.substring(0, equals);
      if (key.trim().equalsIgnoreCase(name)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Warnings the user sees next to the preview.
   *
   * <p>{@code region} is mandatory for an {@code s3://} URL and the failure without it is a driver
   * exception deep in the job log, not a configuration error. Saying so at preview time is the
   * difference between a five-second fix and a ten-minute job that dies at the end.
   */
  public static List<String> warnings(List<DsbulkSetting> settings) {
    String url = connectorUrl(settings);
    if (!isS3(url)) {
      return List.of();
    }
    List<String> warnings = new ArrayList<>();
    String region = valueOf(settings, "s3.region");
    if ((region == null || region.isBlank()) && !alreadyPresent(queryOf(url), "region")) {
      warnings.add("An s3:// URL needs a region: DSBulk 1.11 takes it as a query parameter on the "
          + "URL, and without it the job fails when it first touches the bucket. Set s3.region.");
    }
    for (String path : UNSUPPORTED) {
      String value = valueOf(settings, "s3." + path);
      if (value != null && !value.isBlank()) {
        warnings.add("s3." + path + " does not exist in DSBulk 1.11 - neither as a setting nor as a "
            + "URL parameter - so it has been dropped rather than written into a configuration file "
            + "that would accept it silently and ignore it.");
      }
    }
    return List.copyOf(warnings);
  }

  /* --------------------------------------------------------------------------------- helpers */

  /** The part after {@code s3.}, or {@code null} when the path is not in the group. */
  static String s3Suffix(String path) {
    return path != null && path.startsWith("s3.") ? path.substring(3) : null;
  }

  static boolean isConnectorUrl(String path) {
    return "connector.csv.url".equals(path) || "connector.json.url".equals(path);
  }

  static boolean isS3(String url) {
    return url != null && url.toLowerCase(Locale.ROOT).startsWith("s3://");
  }

  static String connectorUrl(List<DsbulkSetting> settings) {
    String csv = valueOf(settings, "connector.csv.url");
    return csv != null ? csv : valueOf(settings, "connector.json.url");
  }

  private static String queryOf(String url) {
    int query = url.indexOf('?');
    return query < 0 ? "" : url.substring(query + 1);
  }

  private static String valueOf(List<DsbulkSetting> settings, String path) {
    for (DsbulkSetting setting : settings) {
      if (setting.path().equals(path)) {
        return setting.value();
      }
    }
    return null;
  }
}
