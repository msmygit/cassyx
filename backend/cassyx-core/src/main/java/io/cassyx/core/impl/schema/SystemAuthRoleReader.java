package io.cassyx.core.impl.schema;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.datastax.oss.driver.api.core.type.reflect.GenericType;
import io.cassyx.core.api.schema.CqlNames;
import io.cassyx.core.api.schema.CqlPermission;
import io.cassyx.core.api.schema.PermissionGrant;
import io.cassyx.core.api.schema.RoleInfo;
import io.cassyx.core.api.schema.RoleReader;
import io.cassyx.core.api.schema.SchemaIdentity;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Roles and permissions.
 *
 * <p>Roles are not part of the driver's schema metadata, so unlike the rest of the catalog this
 * class issues CQL - {@code LIST ROLES} and {@code LIST ALL PERMISSIONS}, exactly as the contract
 * describes. Nothing here reads {@code system_auth} tables directly: the LIST statements apply the
 * server's own authorisation rules, whereas a raw table read either leaks or fails.
 */
public final class SystemAuthRoleReader implements RoleReader {

  private static final Logger LOG = LoggerFactory.getLogger(SystemAuthRoleReader.class);

  private static final GenericType<Map<String, String>> STRING_MAP =
      GenericType.mapOf(String.class, String.class);

  @Override
  public List<RoleInfo> roles(CqlSession session) {
    List<RoleInfo> roles = new ArrayList<>();
    for (Row row : session.execute("LIST ROLES")) {
      String name = string(row, "role");
      if (name == null) {
        continue;
      }
      roles.add(
          new RoleInfo(
              SchemaIdentity.role(name),
              name,
              bool(row, "super"),
              bool(row, "login"),
              memberOf(session, name),
              options(row)));
    }
    roles.sort(java.util.Comparator.comparing(RoleInfo::name));
    return List.copyOf(roles);
  }

  /**
   * Direct memberships only ({@code NORECURSIVE}). Best effort: a role without {@code DESCRIBE} on
   * another role gets an empty list rather than a failed page.
   */
  private List<String> memberOf(CqlSession session, String role) {
    List<String> parents = new ArrayList<>();
    try {
      SimpleStatement statement =
          SimpleStatement.newInstance("LIST ROLES OF " + CqlNames.quote(role) + " NORECURSIVE");
      for (Row row : session.execute(statement)) {
        String parent = string(row, "role");
        if (parent != null && !parent.equals(role)) {
          parents.add(parent);
        }
      }
    } catch (RuntimeException e) {
      LOG.debug("Could not list memberships of role {}: {}", role, e.toString());
    }
    return parents;
  }

  @Override
  public List<PermissionGrant> permissions(CqlSession session, String role, String resource) {
    StringBuilder cql = new StringBuilder("LIST ALL PERMISSIONS");
    if (resource != null && !resource.isBlank()) {
      cql.append(" ON ").append(resource.trim());
    }
    if (role != null && !role.isBlank()) {
      cql.append(" OF ").append(CqlNames.quote(role));
    }
    List<PermissionGrant> grants = new ArrayList<>();
    for (Row row : session.execute(SimpleStatement.newInstance(cql.toString()))) {
      String grantee = string(row, "role");
      if (grantee == null) {
        grantee = string(row, "username");
      }
      String resourceString = string(row, "resource");
      CqlPermission permission = permission(string(row, "permission"));
      if (grantee == null || permission == null) {
        continue;
      }
      grants.add(
          new PermissionGrant(
              grantee, resourceString, resourceIdentity(resourceString), permission, false));
    }
    return List.copyOf(grants);
  }

  /**
   * Turns the server's {@code <table demo.users>} rendering into a structured identity so the
   * permission matrix can link straight to the object - never re-deriving the keyspace elsewhere.
   */
  static SchemaIdentity resourceIdentity(String resource) {
    if (resource == null) {
      return null;
    }
    String value = resource.trim();
    if (value.startsWith("<") && value.endsWith(">")) {
      value = value.substring(1, value.length() - 1).trim();
    }
    String lower = value.toLowerCase(Locale.ROOT);
    if (lower.startsWith("keyspace ")) {
      return SchemaIdentity.keyspace(value.substring("keyspace ".length()).trim());
    }
    if (lower.startsWith("table ")) {
      String qualified = value.substring("table ".length()).trim();
      int dot = qualified.indexOf('.');
      if (dot > 0) {
        return SchemaIdentity.table(qualified.substring(0, dot), qualified.substring(dot + 1));
      }
      return null;
    }
    if (lower.startsWith("role ")) {
      return SchemaIdentity.role(value.substring("role ".length()).trim());
    }
    return null;
  }

  private static CqlPermission permission(String value) {
    if (value == null) {
      return null;
    }
    try {
      return CqlPermission.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static Map<String, String> options(Row row) {
    if (row.getColumnDefinitions().contains("options")) {
      Map<String, String> options = row.get("options", STRING_MAP);
      return options == null ? Map.of() : options;
    }
    return Map.of();
  }

  private static String string(Row row, String column) {
    return row.getColumnDefinitions().contains(column) ? row.getString(column) : null;
  }

  private static boolean bool(Row row, String column) {
    return row.getColumnDefinitions().contains(column) && row.getBoolean(column);
  }
}
