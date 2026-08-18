package io.cassyx.core.api.schema;

/** {@code CALLED ON NULL INPUT} vs {@code RETURNS NULL ON NULL INPUT} (plan section 4). */
public enum UdfNullHandling {
  CALLED_ON_NULL_INPUT,
  RETURNS_NULL_ON_NULL_INPUT;

  /** The CQL clause this value renders to. */
  public String toCql() {
    return this == CALLED_ON_NULL_INPUT ? "CALLED ON NULL INPUT" : "RETURNS NULL ON NULL INPUT";
  }
}
