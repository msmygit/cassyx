package io.cassyx.api.schema;

import io.cassyx.core.api.schema.SchemaIdentity;

/**
 * Describe an existing object. {@code identity} carries the full qualification, so a describe of
 * {@code demo.users} can never resolve to {@code system_auth.users}.
 */
public record DdlDescribeRequest(
    SchemaIdentity identity, Boolean withChildren, Boolean formatted) {

  public boolean withChildrenOrDefault() {
    return withChildren == null || withChildren;
  }

  public boolean formattedOrDefault() {
    return formatted == null || formatted;
  }
}
