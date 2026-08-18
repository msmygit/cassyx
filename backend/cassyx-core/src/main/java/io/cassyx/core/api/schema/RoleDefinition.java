package io.cassyx.core.api.schema;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * Input to CREATE/ALTER ROLE.
 *
 * <p>{@code password} is write-only. It appears in the generated preview, because the user must be
 * able to review and edit the exact statement before it runs, but it is redacted from the executed
 * statements echoed back in {@link DdlExecutionResult} (plan section 2.3, secrets are never
 * returned).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RoleDefinition(
    String name,
    String password,
    Boolean superuser,
    Boolean login,
    List<String> memberOf,
    Map<String, String> options,
    Boolean ifNotExists) {

  public RoleDefinition {
    memberOf = memberOf == null ? List.of() : List.copyOf(memberOf);
    options = options == null ? Map.of() : Map.copyOf(options);
  }
}
