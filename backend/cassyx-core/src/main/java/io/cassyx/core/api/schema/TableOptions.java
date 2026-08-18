package io.cassyx.core.api.schema;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/**
 * The full {@code WITH} options surface of plan section 4. Every field is optional; {@code null}
 * means "leave alone" on an ALTER and "server default" on a CREATE.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TableOptions(
    String comment,
    CompactionSettings compaction,
    CompressionSettings compression,
    CachingSettings caching,
    Double bloomFilterFpChance,
    Integer gcGraceSeconds,
    Integer defaultTimeToLive,
    String readRepair,
    Double readRepairChance,
    Double dclocalReadRepairChance,
    String speculativeRetry,
    String additionalWritePolicy,
    Integer minIndexInterval,
    Integer maxIndexInterval,
    Integer memtableFlushPeriodInMs,
    Double crcCheckChance,
    Boolean cdc,
    Map<String, String> extensions) {

  public TableOptions {
    extensions = extensions == null ? Map.of() : Map.copyOf(extensions);
  }

  /** An options object with nothing set. */
  public static TableOptions empty() {
    return new TableOptions(
        null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
        null, null, Map.of());
  }

  /** Only a comment - the COMMENT tab's editor. */
  public static TableOptions comment(String comment) {
    return new TableOptions(
        comment, null, null, null, null, null, null, null, null, null, null, null, null, null,
        null, null, null, Map.of());
  }
}
