package io.cassyx.bulk.api.dsbulk;

import java.util.Objects;

/**
 * One resolved DSBulk setting - the {@code DerivedSetting} of the API contract.
 *
 * <p>Everything the UI needs to render an editable "auto" chip that explains itself: what the value
 * is, whether cassyx derived it, what DSBulk's own default would have been (placeholder text),
 * <em>why</em> we chose differently, and where the upstream documentation lives.
 *
 * @param path full DSBulk setting path, e.g. {@code batch.maxBatchStatements}
 * @param value the resolved value exactly as it reaches DSBulk
 * @param auto {@code true} when cassyx derived it, {@code false} when the caller supplied it
 * @param upstreamDefault DSBulk's own default, or {@code null} when it has none
 * @param rationale why this value was chosen; empty for caller-supplied settings
 * @param docsUrl link to the upstream settings reference for this option
 * @param group the accordion group in the Advanced tab
 */
public record DsbulkSetting(
    String path,
    String value,
    boolean auto,
    String upstreamDefault,
    String rationale,
    String docsUrl,
    DsbulkSettingGroup group) {

  public DsbulkSetting {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(value, "value");
    rationale = rationale == null ? "" : rationale;
    group = group == null ? DsbulkSettingGroup.of(path) : group;
  }

  /** A setting cassyx derived, with the reason it can show the user. */
  public static DsbulkSetting derived(
      String path, String value, String upstreamDefault, String rationale, String docsUrl) {
    return new DsbulkSetting(path, value, true, upstreamDefault, rationale, docsUrl, null);
  }

  /** A setting the caller supplied; it renders without an "auto" chip. */
  public static DsbulkSetting override(String path, String value, String upstreamDefault, String docsUrl) {
    return new DsbulkSetting(path, value, false, upstreamDefault, "", docsUrl, null);
  }

  /** Same setting, re-flagged as a caller override (used when a derived value is edited). */
  public DsbulkSetting asOverride(String newValue) {
    return new DsbulkSetting(path, newValue, false, upstreamDefault, "", docsUrl, group);
  }

  /** {@code true} when the value equals what DSBulk would have done anyway. */
  public boolean matchesUpstreamDefault() {
    return value.equals(upstreamDefault);
  }
}
