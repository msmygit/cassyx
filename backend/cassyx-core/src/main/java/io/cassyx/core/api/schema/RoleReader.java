package io.cassyx.core.api.schema;

import com.datastax.oss.driver.api.core.CqlSession;
import java.util.List;

/**
 * Roles and permissions (plan section 4).
 *
 * <p>Not part of the driver's schema metadata, so unlike {@link SchemaReader} this one does issue
 * CQL - {@code LIST ROLES} and {@code LIST ALL PERMISSIONS}.
 */
public interface RoleReader {

  List<RoleInfo> roles(CqlSession session);

  /**
   * @param role optional filter
   * @param resource optional CQL resource string, e.g. {@code table demo.users}
   */
  List<PermissionGrant> permissions(CqlSession session, String role, String resource);
}
