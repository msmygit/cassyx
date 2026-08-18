package io.cassyx.api.bulk;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Flattens the contract's nested {@code DsbulkSettings} object into the flat
 * {@code path -> value} map that {@code cassyx-bulk} works in.
 *
 * <p>Done generically over the parsed JSON rather than by mirroring 120 fields into Java records,
 * and that is a deliberate design choice, not laziness:
 *
 * <ul>
 *   <li><b>It cannot drift from the contract.</b> A hand-mirrored DTO silently drops any field
 *       somebody adds to the spec but forgets to add here - and a dropped bulk setting does not
 *       fail, it just quietly does not apply.
 *   <li><b>The contract already promises this.</b> Every group carries an {@code extra} passthrough
 *       map precisely so "a new DSBulk option never requires an API change" (plan section 5.3). A
 *       flattener that treats every key uniformly delivers that for free.
 * </ul>
 *
 * <p>Type fidelity is preserved on the way through: numbers and booleans render bare, arrays render
 * as JSON (which is valid HOCON), strings render as themselves. {@code DsbulkHocon} then re-quotes
 * as needed.
 */
public final class DsbulkSettingsFlattener {

  /** The per-group escape hatch. Its keys are already full setting paths. */
  static final String EXTRA = "extra";

  private DsbulkSettingsFlattener() {}

  /**
   * @param settings the contract's {@code DsbulkSettings} object, or {@code null}
   * @return setting path to value, with every {@code extra} map applied LAST so it overrides
   */
  public static Map<String, String> flatten(JsonNode settings) {
    Map<String, String> flat = new LinkedHashMap<>();
    Map<String, String> extras = new LinkedHashMap<>();
    if (settings != null && settings.isObject()) {
      walk(settings, "", flat, extras);
    }
    flat.putAll(extras);
    return flat;
  }

  private static void walk(JsonNode node, String prefix, Map<String, String> flat, Map<String, String> extras) {
    Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
    while (fields.hasNext()) {
      Map.Entry<String, JsonNode> field = fields.next();
      String name = field.getKey();
      JsonNode value = field.getValue();
      if (value == null || value.isNull()) {
        continue;
      }
      if (EXTRA.equals(name) && value.isObject()) {
        // Keys here are FULL paths already ("dsbulk.connector.csv.ignoreLeadingWhitespaces"),
        // never relative to the group they were written under.
        value.fields().forEachRemaining(entry -> extras.put(entry.getKey(), render(entry.getValue())));
        continue;
      }
      String path = prefix.isEmpty() ? name : prefix + "." + name;
      if (value.isObject() && !isValueMap(path)) {
        walk(value, path, flat, extras);
      } else {
        flat.put(path, render(value));
      }
    }
  }

  /**
   * Paths whose object value is DATA, not more nesting.
   *
   * <p>{@code connector.json.parserFeatures} and friends are maps of Jackson feature names to
   * booleans, and {@code log.checkpoint} is a free-form map. Recursing into them would invent
   * setting paths that do not exist; DSBulk wants the object itself.
   */
  private static boolean isValueMap(String path) {
    return path.endsWith("Features") || path.equals("log.checkpoint");
  }

  /** Renders a leaf node as the string DSBulk will parse. */
  static String render(JsonNode node) {
    if (node.isTextual()) {
      return node.asText();
    }
    if (node.isArray()) {
      // JSON array syntax is valid HOCON, so this needs no translation.
      List<String> items = new ArrayList<>();
      node.forEach(item -> items.add(item.isTextual() ? quote(item.asText()) : item.toString()));
      return "[" + String.join(",", items) + "]";
    }
    // Numbers, booleans and objects render as their JSON form, which HOCON accepts verbatim.
    return node.toString();
  }

  private static String quote(String value) {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }
}
