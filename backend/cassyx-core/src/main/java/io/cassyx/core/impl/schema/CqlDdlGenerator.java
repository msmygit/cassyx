package io.cassyx.core.impl.schema;

import io.cassyx.core.api.schema.ClusteringKeyColumn;
import io.cassyx.core.api.schema.ColumnAlteration;
import io.cassyx.core.api.schema.ColumnDefinition;
import io.cassyx.core.api.schema.CqlNames;
import io.cassyx.core.api.schema.CqlPermission;
import io.cassyx.core.api.schema.DdlGenerator;
import io.cassyx.core.api.schema.DdlPreview;
import io.cassyx.core.api.schema.FunctionArgument;
import io.cassyx.core.api.schema.IndexDefinition;
import io.cassyx.core.api.schema.IndexKind;
import io.cassyx.core.api.schema.InvalidDefinitionException;
import io.cassyx.core.api.schema.KeyspaceDefinition;
import io.cassyx.core.api.schema.MaterializedViewDefinition;
import io.cassyx.core.api.schema.PermissionChange;
import io.cassyx.core.api.schema.PrimaryKeyDefinition;
import io.cassyx.core.api.schema.ReplicationSettings;
import io.cassyx.core.api.schema.RoleDefinition;
import io.cassyx.core.api.schema.SchemaIdentity;
import io.cassyx.core.api.schema.TableDefinition;
import io.cassyx.core.api.schema.TableOptions;
import io.cassyx.core.api.schema.UserDefinedAggregateDefinition;
import io.cassyx.core.api.schema.UserDefinedFunctionDefinition;
import io.cassyx.core.api.schema.UserDefinedTypeAlteration;
import io.cassyx.core.api.schema.UserDefinedTypeDefinition;
import io.cassyx.core.api.schema.UserDefinedTypeField;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Pure CQL generation. Touches no cluster and executes nothing (plan section 4).
 *
 * <p>Every method returns a {@link DdlPreview} the UI must show and let the user edit before
 * anything runs. Irreversible operations attach a warning rather than a confirmation dialog the
 * backend cannot enforce.
 */
public final class CqlDdlGenerator implements DdlGenerator {

  private static final Pattern VECTOR_TYPE =
      Pattern.compile("(?i)\\bvector\\s*<\\s*float\\s*,\\s*(\\d+)\\s*>");

  private static final String VECTOR_WARNING =
      "vector<float, N> columns require Cassandra 5.x / Astra and driver 4.19.0 to describe "
          + "correctly (CASSJAVA-2).";

  /* ------------------------------------------------------------------ keyspaces */

  @Override
  public DdlPreview createKeyspace(KeyspaceDefinition definition) {
    String name = require(definition.name(), "name", "keyspace name is required");
    String statement =
        "CREATE KEYSPACE "
            + ifNotExists(definition.ifNotExists())
            + CqlNames.quote(name)
            + " WITH replication = "
            + replication(definition.replication())
            + " AND durable_writes = "
            + (definition.durableWrites() == null || definition.durableWrites())
            + ";";
    return DdlPreview.of(SchemaIdentity.keyspace(name), List.of(statement), List.of());
  }

  @Override
  public DdlPreview alterKeyspace(String keyspace, KeyspaceDefinition definition) {
    require(keyspace, "keyspace", "keyspace name is required");
    List<String> parts = new ArrayList<>();
    if (definition.replication() != null) {
      parts.add("replication = " + replication(definition.replication()));
    }
    if (definition.durableWrites() != null) {
      parts.add("durable_writes = " + definition.durableWrites());
    }
    if (parts.isEmpty()) {
      throw new InvalidDefinitionException(
          "replication", "ALTER KEYSPACE needs a replication or durable_writes change");
    }
    String statement =
        "ALTER KEYSPACE " + CqlNames.quote(keyspace) + " WITH " + String.join(" AND ", parts) + ";";
    return DdlPreview.of(SchemaIdentity.keyspace(keyspace), List.of(statement), List.of());
  }

  @Override
  public DdlPreview dropKeyspace(String keyspace, boolean ifExists) {
    require(keyspace, "keyspace", "keyspace name is required");
    String statement = "DROP KEYSPACE " + ifExists(ifExists) + CqlNames.quote(keyspace) + ";";
    return DdlPreview.of(
        SchemaIdentity.keyspace(keyspace),
        List.of(statement),
        List.of("Dropping a keyspace destroys every table, view and type it contains. Irreversible."));
  }

  private String replication(ReplicationSettings replication) {
    if (replication == null || replication.strategy() == null) {
      throw new InvalidDefinitionException("replication", "a replication strategy is required");
    }
    Map<String, String> map = new TreeMap<>();
    map.put("class", replication.strategy().name());
    switch (replication.strategy()) {
      case SimpleStrategy -> {
        if (replication.replicationFactor() == null) {
          throw new InvalidDefinitionException(
              "replication.replicationFactor", "SimpleStrategy requires a replication factor");
        }
        map.put("replication_factor", String.valueOf(replication.replicationFactor()));
      }
      case NetworkTopologyStrategy -> {
        if (replication.datacenters().isEmpty()) {
          throw new InvalidDefinitionException(
              "replication.datacenters",
              "NetworkTopologyStrategy requires at least one datacenter replication factor");
        }
        replication.datacenters().forEach((dc, rf) -> map.put(dc, String.valueOf(rf)));
      }
      default -> {
        if (replication.replicationFactor() != null) {
          map.put("replication_factor", String.valueOf(replication.replicationFactor()));
        }
      }
    }
    return TableOptionsRenderer.stringMap(map);
  }

  /* --------------------------------------------------------------------- tables */

  @Override
  public DdlPreview createTable(String keyspace, TableDefinition definition) {
    require(keyspace, "keyspace", "keyspace is required");
    String name = require(definition.name(), "name", "table name is required");
    if (definition.columns().isEmpty()) {
      throw new InvalidDefinitionException("columns", "a table needs at least one column");
    }
    PrimaryKeyDefinition primaryKey = definition.primaryKey();
    if (primaryKey == null || primaryKey.partitionKey().isEmpty()) {
      throw new InvalidDefinitionException(
          "primaryKey.partitionKey", "a table needs at least one partition key column");
    }
    Set<String> declared = new LinkedHashSet<>();
    definition.columns().forEach(column -> declared.add(column.name()));
    for (String keyColumn : primaryKey.allColumns()) {
      if (!declared.contains(keyColumn)) {
        throw new InvalidDefinitionException(
            "primaryKey", "primary key column '" + keyColumn + "' is not declared in columns");
      }
    }

    List<String> warnings = new ArrayList<>();
    List<String> lines = new ArrayList<>();
    for (ColumnDefinition column : definition.columns()) {
      lines.add("    " + columnDeclaration(column, warnings));
    }
    lines.add("    PRIMARY KEY " + primaryKeyClause(primaryKey));

    List<String> withParts = new ArrayList<>(
        TableOptionsRenderer.clusteringOrder(primaryKey.clusteringKey()));
    withParts.addAll(TableOptionsRenderer.render(definition.options()));

    StringBuilder cql = new StringBuilder();
    cql.append("CREATE TABLE ")
        .append(ifNotExists(definition.ifNotExists()))
        .append(CqlNames.qualify(keyspace, name))
        .append(" (\n")
        .append(String.join(",\n", lines))
        .append("\n)");
    if (!withParts.isEmpty()) {
      cql.append("\n    WITH ").append(String.join("\n    AND ", withParts));
    }
    cql.append(";");
    return DdlPreview.of(SchemaIdentity.table(keyspace, name), List.of(cql.toString()), warnings);
  }

  private String columnDeclaration(ColumnDefinition column, List<String> warnings) {
    String name = require(column.name(), "columns[].name", "column name is required");
    String type = require(column.type(), "columns[].type", "column type is required");
    if (VECTOR_TYPE.matcher(type).find() && !warnings.contains(VECTOR_WARNING)) {
      warnings.add(VECTOR_WARNING);
    }
    return CqlNames.quote(name) + " " + type.trim() + (column.staticColumn() ? " STATIC" : "");
  }

  private String primaryKeyClause(PrimaryKeyDefinition primaryKey) {
    List<String> partition = primaryKey.partitionKey().stream().map(CqlNames::quote).toList();
    String partitionPart =
        partition.size() == 1 ? partition.get(0) : "(" + String.join(", ", partition) + ")";
    List<String> clustering =
        primaryKey.clusteringKey().stream().map(ClusteringKeyColumn::column).map(CqlNames::quote).toList();
    if (clustering.isEmpty()) {
      return "(" + partitionPart + ")";
    }
    return "(" + partitionPart + ", " + String.join(", ", clustering) + ")";
  }

  @Override
  public DdlPreview alterTable(String keyspace, String table, TableOptions options) {
    List<String> parts = TableOptionsRenderer.render(options);
    if (parts.isEmpty()) {
      throw new InvalidDefinitionException("options", "no table options to change");
    }
    String statement =
        "ALTER TABLE " + CqlNames.qualify(keyspace, table) + " WITH " + String.join(" AND ", parts) + ";";
    return DdlPreview.of(SchemaIdentity.table(keyspace, table), List.of(statement), List.of());
  }

  @Override
  public DdlPreview dropTable(String keyspace, String table, boolean ifExists) {
    String statement =
        "DROP TABLE " + ifExists(ifExists) + CqlNames.qualify(keyspace, table) + ";";
    return DdlPreview.of(
        SchemaIdentity.table(keyspace, table),
        List.of(statement),
        List.of("Dropping a table destroys its data. Irreversible."));
  }

  @Override
  public DdlPreview truncateTable(String keyspace, String table) {
    String statement = "TRUNCATE TABLE " + CqlNames.qualify(keyspace, table) + ";";
    return DdlPreview.of(
        SchemaIdentity.table(keyspace, table),
        List.of(statement),
        List.of("TRUNCATE removes every row in the table. Irreversible."));
  }

  /* -------------------------------------------------------------------- columns */

  @Override
  public DdlPreview addColumn(String keyspace, String table, ColumnDefinition definition) {
    List<String> warnings = new ArrayList<>();
    String statement =
        "ALTER TABLE "
            + CqlNames.qualify(keyspace, table)
            + " ADD "
            + columnDeclaration(definition, warnings)
            + ";";
    return DdlPreview.of(
        SchemaIdentity.column(keyspace, table, definition.name()), List.of(statement), warnings);
  }

  @Override
  public DdlPreview alterColumn(
      String keyspace, String table, String column, ColumnAlteration alteration) {
    boolean rename = alteration != null && notBlank(alteration.newName());
    boolean retype = alteration != null && notBlank(alteration.newType());
    if (rename == retype) {
      throw new InvalidDefinitionException(
          "newName", "supply exactly one of newName (rename) or newType (retype)");
    }
    String qualified = CqlNames.qualify(keyspace, table);
    if (rename) {
      String statement =
          "ALTER TABLE "
              + qualified
              + " RENAME "
              + CqlNames.quote(column)
              + " TO "
              + CqlNames.quote(alteration.newName())
              + ";";
      return DdlPreview.of(
          SchemaIdentity.column(keyspace, table, alteration.newName()),
          List.of(statement),
          List.of("Cassandra only permits renaming primary-key columns."));
    }
    String statement =
        "ALTER TABLE "
            + qualified
            + " ALTER "
            + CqlNames.quote(column)
            + " TYPE "
            + alteration.newType().trim()
            + ";";
    return DdlPreview.of(
        SchemaIdentity.column(keyspace, table, column),
        List.of(statement),
        List.of("Retyping a column is subject to the cluster's type-compatibility rules."));
  }

  @Override
  public DdlPreview dropColumn(String keyspace, String table, String column) {
    String statement =
        "ALTER TABLE " + CqlNames.qualify(keyspace, table) + " DROP " + CqlNames.quote(column) + ";";
    return DdlPreview.of(
        SchemaIdentity.column(keyspace, table, column),
        List.of(statement),
        List.of("Dropping a column discards its data. Irreversible."));
  }

  /* -------------------------------------------------------------------- indexes */

  @Override
  public DdlPreview createIndex(String keyspace, String table, IndexDefinition definition) {
    String name = require(definition.name(), "name", "index name is required");
    String target = require(definition.target(), "target", "index target is required");
    IndexKind kind = definition.kind() == null ? IndexKind.COMPOSITES : definition.kind();
    String on =
        CqlNames.quote(name) + " ON " + CqlNames.qualify(keyspace, table) + " (" + target.trim() + ")";

    StringBuilder cql = new StringBuilder();
    List<String> warnings = new ArrayList<>();
    switch (kind) {
      case SAI -> {
        cql.append("CREATE CUSTOM INDEX ")
            .append(ifNotExists(definition.ifNotExists()))
            .append(on)
            .append(" USING ")
            .append(CqlNames.literal(
                definition.className() == null ? IndexDefinition.SAI_CLASS : definition.className()));
        appendOptions(cql, definition.options());
      }
      case DSE_SEARCH -> {
        cql.append("CREATE CUSTOM INDEX ")
            .append(ifNotExists(definition.ifNotExists()))
            .append(on)
            .append(" USING ")
            .append(CqlNames.literal(
                definition.className() == null
                    ? IndexDefinition.DSE_SEARCH_CLASS
                    : definition.className()));
        appendOptions(cql, definition.options());
        warnings.add("DSE Search indexes exist only on DataStax Enterprise.");
      }
      case CUSTOM -> {
        if (!notBlank(definition.className())) {
          throw new InvalidDefinitionException("className", "a CUSTOM index requires a class name");
        }
        cql.append("CREATE CUSTOM INDEX ")
            .append(ifNotExists(definition.ifNotExists()))
            .append(on)
            .append(" USING ")
            .append(CqlNames.literal(definition.className()));
        appendOptions(cql, definition.options());
      }
      case COMPOSITES, KEYS -> {
        cql.append("CREATE INDEX ").append(ifNotExists(definition.ifNotExists())).append(on);
        warnings.add(
            "Legacy 2i indexes scan every replica. Prefer SAI on Cassandra 5.x / Astra.");
      }
      default -> throw new InvalidDefinitionException("kind", "unsupported index kind " + kind);
    }
    cql.append(";");
    return DdlPreview.of(
        SchemaIdentity.index(keyspace, table, name), List.of(cql.toString()), warnings);
  }

  private void appendOptions(StringBuilder cql, Map<String, String> options) {
    if (options != null && !options.isEmpty()) {
      cql.append(" WITH OPTIONS = ").append(TableOptionsRenderer.stringMap(options));
    }
  }

  @Override
  public DdlPreview dropIndex(String keyspace, String table, String index, boolean ifExists) {
    String statement = "DROP INDEX " + ifExists(ifExists) + CqlNames.qualify(keyspace, index) + ";";
    return DdlPreview.of(SchemaIdentity.index(keyspace, table, index), List.of(statement), List.of());
  }

  /* ---------------------------------------------------------- materialized views */

  @Override
  public DdlPreview createMaterializedView(String keyspace, MaterializedViewDefinition definition) {
    String name = require(definition.name(), "name", "view name is required");
    String baseTable = require(definition.baseTable(), "baseTable", "a base table is required");
    PrimaryKeyDefinition primaryKey = definition.primaryKey();
    if (primaryKey == null || primaryKey.partitionKey().isEmpty()) {
      throw new InvalidDefinitionException(
          "primaryKey.partitionKey", "a materialized view needs at least one partition key column");
    }
    String projection =
        definition.selectedColumns().isEmpty()
            ? "*"
            : String.join(", ", definition.selectedColumns().stream().map(CqlNames::quote).toList());
    String where =
        notBlank(definition.whereClause())
            ? definition.whereClause().trim()
            : String.join(
                " AND ",
                primaryKey.allColumns().stream()
                    .map(column -> CqlNames.quote(column) + " IS NOT NULL")
                    .toList());

    List<String> withParts =
        new ArrayList<>(TableOptionsRenderer.clusteringOrder(primaryKey.clusteringKey()));
    withParts.addAll(TableOptionsRenderer.render(definition.options()));

    StringBuilder cql = new StringBuilder();
    cql.append("CREATE MATERIALIZED VIEW ")
        .append(ifNotExists(definition.ifNotExists()))
        .append(CqlNames.qualify(keyspace, name))
        .append(" AS\n    SELECT ")
        .append(projection)
        .append("\n    FROM ")
        .append(CqlNames.qualify(keyspace, baseTable))
        .append("\n    WHERE ")
        .append(where)
        .append("\n    PRIMARY KEY ")
        .append(primaryKeyClause(primaryKey));
    if (!withParts.isEmpty()) {
      cql.append("\n    WITH ").append(String.join("\n    AND ", withParts));
    }
    cql.append(";");
    return DdlPreview.of(
        SchemaIdentity.view(keyspace, name),
        List.of(cql.toString()),
        List.of("Materialized views are unavailable on Astra DB and experimental before Cassandra 5."));
  }

  @Override
  public DdlPreview alterMaterializedView(String keyspace, String view, TableOptions options) {
    List<String> parts = TableOptionsRenderer.render(options);
    if (parts.isEmpty()) {
      throw new InvalidDefinitionException("options", "no view options to change");
    }
    String statement =
        "ALTER MATERIALIZED VIEW "
            + CqlNames.qualify(keyspace, view)
            + " WITH "
            + String.join(" AND ", parts)
            + ";";
    return DdlPreview.of(SchemaIdentity.view(keyspace, view), List.of(statement), List.of());
  }

  @Override
  public DdlPreview dropMaterializedView(String keyspace, String view, boolean ifExists) {
    String statement =
        "DROP MATERIALIZED VIEW " + ifExists(ifExists) + CqlNames.qualify(keyspace, view) + ";";
    return DdlPreview.of(SchemaIdentity.view(keyspace, view), List.of(statement), List.of());
  }

  /* ----------------------------------------------------------------------- UDTs */

  @Override
  public DdlPreview createType(String keyspace, UserDefinedTypeDefinition definition) {
    String name = require(definition.name(), "name", "type name is required");
    if (definition.fields().isEmpty()) {
      throw new InvalidDefinitionException("fields", "a UDT needs at least one field");
    }
    List<String> lines = new ArrayList<>();
    for (UserDefinedTypeField field : definition.fields()) {
      lines.add("    " + CqlNames.quote(field.name()) + " " + field.type().trim());
    }
    String statement =
        "CREATE TYPE "
            + ifNotExists(definition.ifNotExists())
            + CqlNames.qualify(keyspace, name)
            + " (\n"
            + String.join(",\n", lines)
            + "\n);";
    return DdlPreview.of(SchemaIdentity.type(keyspace, name), List.of(statement), List.of());
  }

  @Override
  public DdlPreview alterType(String keyspace, String type, UserDefinedTypeAlteration alteration) {
    if (alteration == null
        || (alteration.addFields().isEmpty() && alteration.renameFields().isEmpty())) {
      throw new InvalidDefinitionException("addFields", "nothing to alter on this type");
    }
    String qualified = CqlNames.qualify(keyspace, type);
    List<String> statements = new ArrayList<>();
    for (UserDefinedTypeField field : alteration.addFields()) {
      statements.add(
          "ALTER TYPE " + qualified + " ADD " + CqlNames.quote(field.name()) + " " + field.type().trim() + ";");
    }
    if (!alteration.renameFields().isEmpty()) {
      List<String> renames = new ArrayList<>();
      for (Map.Entry<String, String> entry : new TreeMap<>(alteration.renameFields()).entrySet()) {
        renames.add(CqlNames.quote(entry.getKey()) + " TO " + CqlNames.quote(entry.getValue()));
      }
      statements.add("ALTER TYPE " + qualified + " RENAME " + String.join(" AND ", renames) + ";");
    }
    return DdlPreview.of(
        SchemaIdentity.type(keyspace, type),
        statements,
        List.of("CQL can add and rename UDT fields; it cannot drop them."));
  }

  @Override
  public DdlPreview dropType(String keyspace, String type, boolean ifExists) {
    String statement = "DROP TYPE " + ifExists(ifExists) + CqlNames.qualify(keyspace, type) + ";";
    return DdlPreview.of(
        SchemaIdentity.type(keyspace, type),
        List.of(statement),
        List.of("A type still referenced by a column cannot be dropped."));
  }

  /* ----------------------------------------------------------------- UDFs / UDAs */

  @Override
  public DdlPreview createFunction(String keyspace, UserDefinedFunctionDefinition definition) {
    String name = require(definition.name(), "name", "function name is required");
    String returnType = require(definition.returnType(), "returnType", "a return type is required");
    String language = require(definition.language(), "language", "a language is required");
    String body = require(definition.body(), "body", "a function body is required");
    List<String> args = new ArrayList<>();
    for (FunctionArgument argument : definition.arguments()) {
      args.add(CqlNames.quote(argument.name()) + " " + argument.type().trim());
    }
    String nullHandling =
        (definition.nullHandling() == null
                ? io.cassyx.core.api.schema.UdfNullHandling.CALLED_ON_NULL_INPUT
                : definition.nullHandling())
            .toCql();
    String statement =
        "CREATE "
            + (Boolean.TRUE.equals(definition.orReplace()) ? "OR REPLACE " : "")
            + "FUNCTION "
            + (Boolean.TRUE.equals(definition.orReplace()) ? "" : ifNotExists(definition.ifNotExists()))
            + CqlNames.qualify(keyspace, name)
            + " ("
            + String.join(", ", args)
            + ")\n    "
            + nullHandling
            + "\n    RETURNS "
            + returnType.trim()
            + "\n    LANGUAGE "
            + language.trim()
            + "\n    AS $$"
            + body
            + "$$;";
    return DdlPreview.of(
        SchemaIdentity.function(keyspace, name, signatureOf(definition.arguments())),
        List.of(statement),
        List.of("User-defined functions are unavailable on Astra DB and require enable_user_defined_functions."));
  }

  @Override
  public DdlPreview dropFunction(String keyspace, String signature, boolean ifExists) {
    String name = nameOfSignature(signature);
    String statement =
        "DROP FUNCTION "
            + ifExists(ifExists)
            + CqlNames.qualify(keyspace, name)
            + argsOfSignature(signature)
            + ";";
    return DdlPreview.of(
        SchemaIdentity.function(keyspace, name, argsOfSignature(signature)),
        List.of(statement),
        List.of());
  }

  @Override
  public DdlPreview createAggregate(String keyspace, UserDefinedAggregateDefinition definition) {
    String name = require(definition.name(), "name", "aggregate name is required");
    String stateFunction =
        require(definition.stateFunction(), "stateFunction", "a state function is required");
    String stateType = require(definition.stateType(), "stateType", "a state type is required");
    StringBuilder cql = new StringBuilder();
    cql.append("CREATE ")
        .append(Boolean.TRUE.equals(definition.orReplace()) ? "OR REPLACE " : "")
        .append("AGGREGATE ")
        .append(Boolean.TRUE.equals(definition.orReplace()) ? "" : ifNotExists(definition.ifNotExists()))
        .append(CqlNames.qualify(keyspace, name))
        .append(" (")
        .append(String.join(", ", definition.argumentTypes()))
        .append(")\n    SFUNC ")
        .append(CqlNames.quote(stateFunction))
        .append("\n    STYPE ")
        .append(stateType.trim());
    if (notBlank(definition.finalFunction())) {
      cql.append("\n    FINALFUNC ").append(CqlNames.quote(definition.finalFunction()));
    }
    if (notBlank(definition.initCondition())) {
      cql.append("\n    INITCOND ").append(definition.initCondition().trim());
    }
    cql.append(";");
    return DdlPreview.of(
        SchemaIdentity.aggregate(
            keyspace, name, "(" + String.join(",", definition.argumentTypes()) + ")"),
        List.of(cql.toString()),
        List.of("User-defined aggregates are unavailable on Astra DB."));
  }

  @Override
  public DdlPreview dropAggregate(String keyspace, String signature, boolean ifExists) {
    String name = nameOfSignature(signature);
    String statement =
        "DROP AGGREGATE "
            + ifExists(ifExists)
            + CqlNames.qualify(keyspace, name)
            + argsOfSignature(signature)
            + ";";
    return DdlPreview.of(
        SchemaIdentity.aggregate(keyspace, name, argsOfSignature(signature)),
        List.of(statement),
        List.of());
  }

  /* -------------------------------------------------------- roles & permissions */

  @Override
  public DdlPreview createRole(RoleDefinition definition) {
    String name = require(definition.name(), "name", "role name is required");
    List<String> statements = new ArrayList<>();
    statements.add(
        "CREATE ROLE "
            + ifNotExists(definition.ifNotExists())
            + CqlNames.quote(name)
            + roleOptions(definition, true)
            + ";");
    for (String parent : definition.memberOf()) {
      statements.add("GRANT " + CqlNames.quote(parent) + " TO " + CqlNames.quote(name) + ";");
    }
    return DdlPreview.of(
        SchemaIdentity.role(name),
        statements,
        definition.password() == null
            ? List.of()
            : List.of("This preview contains a plaintext password; it is redacted from the execution result."));
  }

  @Override
  public DdlPreview alterRole(String role, RoleDefinition definition) {
    require(role, "role", "role name is required");
    String options = roleOptions(definition, false);
    if (options.isEmpty()) {
      throw new InvalidDefinitionException("options", "no role attributes to change");
    }
    String statement = "ALTER ROLE " + CqlNames.quote(role) + options + ";";
    return DdlPreview.of(SchemaIdentity.role(role), List.of(statement), List.of());
  }

  @Override
  public DdlPreview dropRole(String role, boolean ifExists) {
    String statement = "DROP ROLE " + ifExists(ifExists) + CqlNames.quote(role) + ";";
    return DdlPreview.of(SchemaIdentity.role(role), List.of(statement), List.of());
  }

  private String roleOptions(RoleDefinition definition, boolean applyDefaults) {
    List<String> parts = new ArrayList<>();
    if (definition.password() != null) {
      parts.add("PASSWORD = " + CqlNames.literal(definition.password()));
    }
    if (definition.login() != null) {
      parts.add("LOGIN = " + definition.login());
    } else if (applyDefaults) {
      parts.add("LOGIN = true");
    }
    if (definition.superuser() != null) {
      parts.add("SUPERUSER = " + definition.superuser());
    } else if (applyDefaults) {
      parts.add("SUPERUSER = false");
    }
    if (!definition.options().isEmpty()) {
      parts.add("OPTIONS = " + TableOptionsRenderer.stringMap(definition.options()));
    }
    return parts.isEmpty() ? "" : " WITH " + String.join(" AND ", parts);
  }

  @Override
  public DdlPreview grant(PermissionChange change) {
    return permissionStatements(change, "GRANT", "TO");
  }

  @Override
  public DdlPreview revoke(PermissionChange change) {
    return permissionStatements(change, "REVOKE", "FROM");
  }

  private DdlPreview permissionStatements(PermissionChange change, String verb, String preposition) {
    String role = require(change.role(), "role", "a role is required");
    String resource = require(change.resource(), "resource", "a resource is required");
    if (change.permissions().isEmpty()) {
      throw new InvalidDefinitionException("permissions", "at least one permission is required");
    }
    List<String> statements = new ArrayList<>();
    for (CqlPermission permission : change.permissions()) {
      String rendered = permission == CqlPermission.ALL ? "ALL PERMISSIONS" : permission.name();
      statements.add(
          verb
              + " "
              + rendered
              + " ON "
              + resource.trim()
              + " "
              + preposition
              + " "
              + CqlNames.quote(role)
              + ";");
    }
    return DdlPreview.of(SchemaIdentity.role(role), statements, List.of());
  }

  /* -------------------------------------------------------------------- helpers */

  private static String signatureOf(List<FunctionArgument> arguments) {
    return "(" + String.join(",", arguments.stream().map(FunctionArgument::type).toList()) + ")";
  }

  /** {@code avg_state(double,int)} -> {@code avg_state}. */
  static String nameOfSignature(String signature) {
    String value = require(signature, "signature", "a signature is required").trim();
    int paren = value.indexOf('(');
    return paren < 0 ? value : value.substring(0, paren).trim();
  }

  /** {@code avg_state(double,int)} -> {@code (double,int)}; bare names yield {@code ()}. */
  static String argsOfSignature(String signature) {
    String value = require(signature, "signature", "a signature is required").trim();
    int paren = value.indexOf('(');
    if (paren < 0 || !value.endsWith(")")) {
      return "()";
    }
    String inner = value.substring(paren + 1, value.length() - 1).trim();
    if (inner.isEmpty()) {
      return "()";
    }
    List<String> types = new ArrayList<>();
    for (String type : inner.split(",")) {
      types.add(type.trim().toLowerCase(Locale.ROOT));
    }
    return "(" + String.join(", ", types) + ")";
  }

  private static String ifNotExists(Boolean flag) {
    return flag == null || flag ? "IF NOT EXISTS " : "";
  }

  private static String ifExists(boolean flag) {
    return flag ? "IF EXISTS " : "";
  }

  private static boolean notBlank(String value) {
    return value != null && !value.isBlank();
  }

  private static String require(String value, String field, String message) {
    if (value == null || value.isBlank()) {
      throw new InvalidDefinitionException(field, message);
    }
    return value;
  }
}
