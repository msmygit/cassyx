package io.cassyx.bulk.api.dsbulk;

/**
 * One line tailed out of the DSBulk child process, normalised to the contract's {@code JobLogEvent}
 * shape.
 *
 * @param level TRACE | DEBUG | INFO | WARN | ERROR
 * @param message the message text with the level and timestamp stripped
 * @param raw the original line, retained for the downloadable log artifact
 */
public record DsbulkLogLine(String level, String message, String raw) {

  public DsbulkLogLine {
    level = level == null || level.isBlank() ? "INFO" : level;
    message = message == null ? "" : message;
    raw = raw == null ? message : raw;
  }
}
