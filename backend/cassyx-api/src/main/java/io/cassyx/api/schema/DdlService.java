package io.cassyx.api.schema;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.cassyx.core.api.schema.ColumnAlteration;
import io.cassyx.core.api.schema.ColumnDefinition;
import io.cassyx.core.api.schema.DdlExecutionResult;
import io.cassyx.core.api.schema.DdlExecutor;
import io.cassyx.core.api.schema.DdlGenerator;
import io.cassyx.core.api.schema.DdlPreview;
import io.cassyx.core.api.schema.IndexDefinition;
import io.cassyx.core.api.schema.InvalidDefinitionException;
import io.cassyx.core.api.schema.KeyspaceDefinition;
import io.cassyx.core.api.schema.MaterializedViewDefinition;
import io.cassyx.core.api.schema.PermissionChange;
import io.cassyx.core.api.schema.RoleDefinition;
import io.cassyx.core.api.schema.TableDefinition;
import io.cassyx.core.api.schema.TableOptions;
import io.cassyx.core.api.schema.UserDefinedAggregateDefinition;
import io.cassyx.core.api.schema.UserDefinedFunctionDefinition;
import io.cassyx.core.api.schema.UserDefinedTypeAlteration;
import io.cassyx.core.api.schema.UserDefinedTypeDefinition;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Generate-then-execute, as one seam.
 *
 * <p>THE rule of plan section 4: generated DDL is never executed silently. Every mutating endpoint
 * goes through {@link #apply}, which generates a {@link DdlPreview} first and returns exactly the
 * statements it ran, so the client can always show the user what happened - and, via
 * {@code /ddl/generate} plus {@code /ddl/execute}, edit it beforehand.
 */
@Service
public class DdlService {

  private final DdlGenerator generator;
  private final DdlExecutor executor;
  private final SchemaSessions sessions;
  private final ObjectMapper mapper;

  public DdlService(
      DdlGenerator generator, DdlExecutor executor, SchemaSessions sessions, ObjectMapper mapper) {
    this.generator = generator;
    this.executor = executor;
    this.sessions = sessions;
    this.mapper = mapper;
  }

  public DdlGenerator generator() {
    return generator;
  }

  /** Runs an already-generated preview against the connection's live session. */
  public DdlExecutionResult apply(String connectionId, DdlPreview preview) {
    return executor.execute(sessions.session(connectionId), preview, true);
  }

  /** Runs user-reviewed CQL, split with the real lexer. */
  public DdlExecutionResult execute(
      String connectionId, io.cassyx.core.api.schema.DdlExecuteRequest request) {
    return executor.execute(sessions.session(connectionId), request);
  }

  /**
   * The pure "Preview CQL" path: never touches the cluster.
   *
   * @throws InvalidDefinitionException if the object type and action do not name a real operation
   */
  public DdlPreview generate(DdlGenerateRequest request) {
    if (request == null || request.objectType() == null || request.action() == null) {
      throw new InvalidDefinitionException("objectType", "objectType and action are required");
    }
    Map<String, Object> definition =
        request.definition() == null ? Map.of() : request.definition();
    String keyspace = request.keyspace();
    String table = request.table();

    return switch (request.objectType()) {
      case KEYSPACE -> keyspace(request, definition, keyspace);
      case TABLE -> table(request, definition, keyspace, table);
      case COLUMN -> column(request, definition, keyspace, table);
      case INDEX -> index(request, definition, keyspace, table);
      case MATERIALIZED_VIEW -> view(request, definition, keyspace);
      case TYPE -> type(request, definition, keyspace);
      case FUNCTION -> function(request, definition, keyspace);
      case AGGREGATE -> aggregate(request, definition, keyspace);
      case ROLE -> role(request, definition);
      case PERMISSION -> permission(request, definition);
      default -> throw unsupported(request);
    };
  }

  private DdlPreview keyspace(DdlGenerateRequest request, Map<String, Object> definition, String keyspace) {
    return switch (request.action()) {
      case CREATE -> generator.createKeyspace(convert(definition, KeyspaceDefinition.class));
      case ALTER -> generator.alterKeyspace(
          nameOrKeyspace(keyspace, definition), convert(definition, KeyspaceDefinition.class));
      case DROP -> generator.dropKeyspace(nameOrKeyspace(keyspace, definition), true);
      default -> throw unsupported(request);
    };
  }

  private DdlPreview table(
      DdlGenerateRequest request, Map<String, Object> definition, String keyspace, String table) {
    return switch (request.action()) {
      case CREATE -> generator.createTable(keyspace, convert(definition, TableDefinition.class));
      case ALTER -> generator.alterTable(
          keyspace, nameOrTable(table, definition), convert(definition, TableOptions.class));
      case DROP -> generator.dropTable(keyspace, nameOrTable(table, definition), true);
      case TRUNCATE -> generator.truncateTable(keyspace, nameOrTable(table, definition));
      default -> throw unsupported(request);
    };
  }

  private DdlPreview column(
      DdlGenerateRequest request, Map<String, Object> definition, String keyspace, String table) {
    return switch (request.action()) {
      case CREATE -> generator.addColumn(keyspace, table, convert(definition, ColumnDefinition.class));
      case ALTER -> generator.alterColumn(
          keyspace, table, string(definition, "name"), convert(definition, ColumnAlteration.class));
      case DROP -> generator.dropColumn(keyspace, table, string(definition, "name"));
      default -> throw unsupported(request);
    };
  }

  private DdlPreview index(
      DdlGenerateRequest request, Map<String, Object> definition, String keyspace, String table) {
    return switch (request.action()) {
      case CREATE -> generator.createIndex(keyspace, table, convert(definition, IndexDefinition.class));
      case DROP -> generator.dropIndex(keyspace, table, string(definition, "name"), true);
      default -> throw unsupported(request);
    };
  }

  private DdlPreview view(DdlGenerateRequest request, Map<String, Object> definition, String keyspace) {
    return switch (request.action()) {
      case CREATE -> generator.createMaterializedView(
          keyspace, convert(definition, MaterializedViewDefinition.class));
      case ALTER -> generator.alterMaterializedView(
          keyspace, string(definition, "name"), convert(definition, TableOptions.class));
      case DROP -> generator.dropMaterializedView(keyspace, string(definition, "name"), true);
      default -> throw unsupported(request);
    };
  }

  private DdlPreview type(DdlGenerateRequest request, Map<String, Object> definition, String keyspace) {
    return switch (request.action()) {
      case CREATE -> generator.createType(keyspace, convert(definition, UserDefinedTypeDefinition.class));
      case ALTER -> generator.alterType(
          keyspace, string(definition, "name"), convert(definition, UserDefinedTypeAlteration.class));
      case DROP -> generator.dropType(keyspace, string(definition, "name"), true);
      default -> throw unsupported(request);
    };
  }

  private DdlPreview function(
      DdlGenerateRequest request, Map<String, Object> definition, String keyspace) {
    return switch (request.action()) {
      case CREATE -> generator.createFunction(
          keyspace, convert(definition, UserDefinedFunctionDefinition.class));
      case DROP -> generator.dropFunction(keyspace, signature(definition), true);
      default -> throw unsupported(request);
    };
  }

  private DdlPreview aggregate(
      DdlGenerateRequest request, Map<String, Object> definition, String keyspace) {
    return switch (request.action()) {
      case CREATE -> generator.createAggregate(
          keyspace, convert(definition, UserDefinedAggregateDefinition.class));
      case DROP -> generator.dropAggregate(keyspace, signature(definition), true);
      default -> throw unsupported(request);
    };
  }

  private DdlPreview role(DdlGenerateRequest request, Map<String, Object> definition) {
    return switch (request.action()) {
      case CREATE -> generator.createRole(convert(definition, RoleDefinition.class));
      case ALTER -> generator.alterRole(
          string(definition, "name"), convert(definition, RoleDefinition.class));
      case DROP -> generator.dropRole(string(definition, "name"), true);
      default -> throw unsupported(request);
    };
  }

  private DdlPreview permission(DdlGenerateRequest request, Map<String, Object> definition) {
    PermissionChange change = convert(definition, PermissionChange.class);
    return switch (request.action()) {
      case GRANT -> generator.grant(change);
      case REVOKE -> generator.revoke(change);
      default -> throw unsupported(request);
    };
  }

  private <T> T convert(Map<String, Object> definition, Class<T> type) {
    try {
      return mapper.convertValue(definition, type);
    } catch (IllegalArgumentException e) {
      throw new InvalidDefinitionException(
          "definition", "definition does not match " + type.getSimpleName() + ": " + e.getMessage());
    }
  }

  private static String string(Map<String, Object> definition, String key) {
    Object value = definition.get(key);
    if (value == null || value.toString().isBlank()) {
      throw new InvalidDefinitionException("definition." + key, key + " is required");
    }
    return value.toString();
  }

  private static String signature(Map<String, Object> definition) {
    Object value = definition.get("signature");
    return value == null ? string(definition, "name") : value.toString();
  }

  private static String nameOrKeyspace(String keyspace, Map<String, Object> definition) {
    return keyspace == null || keyspace.isBlank() ? string(definition, "name") : keyspace;
  }

  private static String nameOrTable(String table, Map<String, Object> definition) {
    return table == null || table.isBlank() ? string(definition, "name") : table;
  }

  private static InvalidDefinitionException unsupported(DdlGenerateRequest request) {
    return new InvalidDefinitionException(
        "action", request.action() + " is not a valid action for " + request.objectType());
  }
}
