package io.cassyx.bulk.api.dsbulk;

import java.util.Locale;

/**
 * The DSBulk configuration namespaces, one-for-one with the accordion groups of the Advanced tab
 * (plan section 5.3) and with the {@code DerivedSetting.group} enum in the API contract.
 */
public enum DsbulkSettingGroup {
  CONNECTOR,
  SCHEMA,
  BATCH,
  CODEC,
  ENGINE,
  EXECUTOR,
  LOG,
  MONITORING,
  DRIVER,
  S3,
  STATS;

  /** The lower-case name used by the contract. */
  public String contractName() {
    return name().toLowerCase(Locale.ROOT);
  }

  /**
   * Resolves the group owning a full setting path.
   *
   * <p>Driver settings are the awkward case: DSBulk 1.x expresses them in the driver's own
   * {@code datastax-java-driver} namespace rather than under {@code dsbulk}, so both spellings must
   * land in {@link #DRIVER}.
   */
  public static DsbulkSettingGroup of(String path) {
    if (path == null || path.isBlank()) {
      throw new DsbulkException("Empty DSBulk setting path");
    }
    String head = path.startsWith("dsbulk.") ? path.substring("dsbulk.".length()) : path;
    if (head.startsWith("datastax-java-driver") || head.startsWith("driver.")) {
      return DRIVER;
    }
    int dot = head.indexOf('.');
    String first = dot < 0 ? head : head.substring(0, dot);
    for (DsbulkSettingGroup group : values()) {
      if (group.contractName().equals(first)) {
        return group;
      }
    }
    throw new DsbulkException("Unknown DSBulk setting group for path '" + path + "'");
  }
}
