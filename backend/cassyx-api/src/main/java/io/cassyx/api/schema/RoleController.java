package io.cassyx.api.schema;

import io.cassyx.core.api.Capability;
import io.cassyx.core.api.schema.DdlExecutionResult;
import io.cassyx.core.api.schema.PermissionChange;
import io.cassyx.core.api.schema.PermissionGrant;
import io.cassyx.core.api.schema.RoleDefinition;
import io.cassyx.core.api.schema.RoleInfo;
import io.cassyx.core.api.schema.RoleReader;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Roles and the permission matrix (plan section 4), gated on {@code rolesPermissions} - Astra is
 * partial and Amazon Keyspaces uses IAM instead (plan section 7.1).
 *
 * <p>Role passwords travel in the request body only. They appear in the generated preview, because
 * the user must be able to review the statement, but never in an execution result.
 */
@RestController
@RequestMapping("/api/connections/{connectionId}")
public class RoleController {

  private static final String UNSUPPORTED =
      "Role management is unavailable on this cluster (Amazon Keyspaces uses IAM, Astra DB only "
          + "supports it partially).";

  private final RoleReader roles;
  private final SchemaSessions sessions;
  private final DdlService ddl;

  public RoleController(RoleReader roles, SchemaSessions sessions, DdlService ddl) {
    this.roles = roles;
    this.sessions = sessions;
    this.ddl = ddl;
  }

  @GetMapping("/roles")
  public List<RoleInfo> list(@PathVariable String connectionId) {
    sessions.require(connectionId, Capability.ROLES_PERMISSIONS, UNSUPPORTED);
    return roles.roles(sessions.session(connectionId));
  }

  @PostMapping("/roles")
  @ResponseStatus(HttpStatus.CREATED)
  public DdlExecutionResult create(
      @PathVariable String connectionId, @RequestBody RoleDefinition definition) {
    sessions.require(connectionId, Capability.ROLES_PERMISSIONS, UNSUPPORTED);
    return ddl.apply(connectionId, ddl.generator().createRole(definition));
  }

  @PutMapping("/roles/{role}")
  public DdlExecutionResult alter(
      @PathVariable String connectionId,
      @PathVariable String role,
      @RequestBody RoleDefinition definition) {
    sessions.require(connectionId, Capability.ROLES_PERMISSIONS, UNSUPPORTED);
    return ddl.apply(connectionId, ddl.generator().alterRole(role, definition));
  }

  @DeleteMapping("/roles/{role}")
  public DdlExecutionResult drop(
      @PathVariable String connectionId,
      @PathVariable String role,
      @RequestParam(defaultValue = "true") boolean ifExists) {
    sessions.require(connectionId, Capability.ROLES_PERMISSIONS, UNSUPPORTED);
    return ddl.apply(connectionId, ddl.generator().dropRole(role, ifExists));
  }

  @GetMapping("/permissions")
  public List<PermissionGrant> permissions(
      @PathVariable String connectionId,
      @RequestParam(required = false) String role,
      @RequestParam(required = false) String resource) {
    sessions.require(connectionId, Capability.ROLES_PERMISSIONS, UNSUPPORTED);
    return roles.permissions(sessions.session(connectionId), role, resource);
  }

  @PostMapping("/permissions/grant")
  public DdlExecutionResult grant(
      @PathVariable String connectionId, @RequestBody PermissionChange change) {
    sessions.require(connectionId, Capability.ROLES_PERMISSIONS, UNSUPPORTED);
    return ddl.apply(connectionId, ddl.generator().grant(change));
  }

  @PostMapping("/permissions/revoke")
  public DdlExecutionResult revoke(
      @PathVariable String connectionId, @RequestBody PermissionChange change) {
    sessions.require(connectionId, Capability.ROLES_PERMISSIONS, UNSUPPORTED);
    return ddl.apply(connectionId, ddl.generator().revoke(change));
  }
}
