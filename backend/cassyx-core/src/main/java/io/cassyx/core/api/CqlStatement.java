package io.cassyx.core.api;

/**
 * A single statement carved out of a script, with its character offsets so the editor can map
 * "execute statement under cursor" back to a selection.
 */
public record CqlStatement(String text, int startOffset, int endOffset) {

  public boolean contains(int offset) {
    return offset >= startOffset && offset <= endOffset;
  }
}
