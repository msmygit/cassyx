package io.cassyx.core.impl.schema;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps role passwords out of response bodies.
 *
 * <p>A generated preview legitimately contains the password the user just typed - they must be able
 * to review and edit the exact statement. The execution result, however, is a response like any
 * other, and plan section 2.3 is absolute: secrets appear in request schemas only.
 */
final class DdlSecrets {

  private static final Pattern PASSWORD = Pattern.compile("(?i)(PASSWORD\\s*=\\s*)'(?:[^']|'')*'");

  private DdlSecrets() {}

  /** Replaces every {@code PASSWORD = '...'} literal with a fixed mask. */
  static String redact(String cql) {
    if (cql == null) {
      return null;
    }
    Matcher matcher = PASSWORD.matcher(cql);
    return matcher.replaceAll(match -> Matcher.quoteReplacement(match.group(1) + "'***'"));
  }
}
