package io.cassyx.core.api.schema;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/** A role, as returned by {@code LIST ROLES}. Passwords are never read back. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RoleInfo(
    SchemaIdentity identity,
    String name,
    boolean superuser,
    boolean login,
    List<String> memberOf,
    Map<String, String> options) {

  public RoleInfo {
    memberOf = memberOf == null ? List.of() : List.copyOf(memberOf);
    options = options == null ? Map.of() : Map.copyOf(options);
  }
}
