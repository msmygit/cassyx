package io.cassyx.core.api.schema;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * One node of the schema tree.
 *
 * <p>{@code identity} is authoritative. Clients must resolve every action from it and never from
 * the node's position in the tree (plan sections 1 and 4).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SchemaNode(
    SchemaIdentity identity,
    String label,
    SchemaObjectKind kind,
    boolean system,
    String detail,
    List<SchemaNode> children) {

  public SchemaNode {
    children = children == null ? List.of() : List.copyOf(children);
  }

  public static SchemaNode leaf(
      SchemaIdentity identity, String label, SchemaObjectKind kind, boolean system, String detail) {
    return new SchemaNode(identity, label, kind, system, detail, List.of());
  }
}
