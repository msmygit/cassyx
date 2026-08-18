package io.cassyx.core.api.schema;

import com.fasterxml.jackson.annotation.JsonInclude;

/** One cell of the permission matrix, as returned by {@code LIST ALL PERMISSIONS}. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PermissionGrant(
    String role,
    String resource,
    SchemaIdentity resourceIdentity,
    CqlPermission permission,
    boolean grantable) {}
