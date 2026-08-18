package io.cassyx.core.api.schema;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** Input to GRANT / REVOKE. {@code resource} is a CQL resource string, e.g. {@code table demo.users}. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PermissionChange(String role, String resource, List<CqlPermission> permissions) {

  public PermissionChange {
    permissions = permissions == null ? List.of() : List.copyOf(permissions);
  }
}
