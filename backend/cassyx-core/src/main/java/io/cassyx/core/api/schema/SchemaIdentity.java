package io.cassyx.core.api.schema;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * THE fully-qualified identity of a schema object.
 *
 * <p>Every node in every schema response carries one of these, and every action - drop, describe,
 * select, drag - resolves from the node's own identity, never from a parent node or from a UI tree
 * position. This is the fix for the prior-art bug where dragging {@code demo.users} produced
 * {@code SELECT * FROM system_auth.users} (plan sections 1, 4 and 7.3).
 *
 * <p>{@code keyspace} is always present. The remaining fields are populated according to
 * {@link #kind}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SchemaIdentity(
    SchemaObjectKind kind,
    String keyspace,
    String table,
    String view,
    String column,
    String index,
    String name,
    String signature,
    String qualifiedName) {

  public static SchemaIdentity keyspace(String keyspace) {
    return new SchemaIdentity(
        SchemaObjectKind.KEYSPACE, keyspace, null, null, null, null, null, null,
        CqlNames.quote(keyspace));
  }

  public static SchemaIdentity table(String keyspace, String table) {
    return new SchemaIdentity(
        SchemaObjectKind.TABLE, keyspace, table, null, null, null, null, null,
        CqlNames.qualify(keyspace, table));
  }

  public static SchemaIdentity column(String keyspace, String table, String column) {
    return new SchemaIdentity(
        SchemaObjectKind.COLUMN, keyspace, table, null, column, null, null, null,
        CqlNames.qualify(keyspace, table) + "." + CqlNames.quote(column));
  }

  /** An index carries its base table so a drop never has to guess where it lives. */
  public static SchemaIdentity index(String keyspace, String table, String index) {
    return new SchemaIdentity(
        SchemaObjectKind.INDEX, keyspace, table, null, null, index, null, null,
        CqlNames.qualify(keyspace, index));
  }

  public static SchemaIdentity view(String keyspace, String view) {
    return new SchemaIdentity(
        SchemaObjectKind.VIEW, keyspace, null, view, null, null, null, null,
        CqlNames.qualify(keyspace, view));
  }

  public static SchemaIdentity type(String keyspace, String name) {
    return new SchemaIdentity(
        SchemaObjectKind.TYPE, keyspace, null, null, null, null, name, null,
        CqlNames.qualify(keyspace, name));
  }

  public static SchemaIdentity function(String keyspace, String name, String signature) {
    return new SchemaIdentity(
        SchemaObjectKind.FUNCTION, keyspace, null, null, null, null, name, signature,
        CqlNames.qualify(keyspace, name) + (signature == null ? "" : signature));
  }

  public static SchemaIdentity aggregate(String keyspace, String name, String signature) {
    return new SchemaIdentity(
        SchemaObjectKind.AGGREGATE, keyspace, null, null, null, null, name, signature,
        CqlNames.qualify(keyspace, name) + (signature == null ? "" : signature));
  }

  /** Roles are cluster-scoped; {@code system_auth} is used as their nominal keyspace. */
  public static SchemaIdentity role(String name) {
    return new SchemaIdentity(
        SchemaObjectKind.ROLE, "system_auth", null, null, null, null, name, null,
        CqlNames.quote(name));
  }

  /** The table or view this identity targets, whichever is set. */
  public String tableOrView() {
    return table != null ? table : view;
  }
}
