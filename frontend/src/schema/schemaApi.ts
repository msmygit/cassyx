/**
 * Typed call sites for every `schema`-tagged operation in `openapi/cassyx-api.yaml` (plan §4).
 *
 * One function per contract `operationId`, named after it, so a spec change and a client change
 * are the same review.
 */
import { apiClient, type ApiClient } from '../api/client';
import type {
  ApiSchemaSearchResult,
  ApiSchemaTree,
  Column,
  ColumnAlteration,
  ColumnDefinition,
  DdlDescribeRequest,
  DdlExecuteRequest,
  DdlExecutionResult,
  DdlGenerateRequest,
  DdlPreview,
  Index,
  IndexDefinition,
  Keyspace,
  KeyspaceDefinition,
  MaterializedView,
  MaterializedViewDefinition,
  PermissionChangeRequest,
  PermissionGrant,
  Role,
  RoleDefinition,
  Table,
  TableCommentUpdate,
  TableDefinition,
  TableInfo,
  TableOptions,
  TableStatistics,
  UserDefinedAggregate,
  UserDefinedAggregateDefinition,
  UserDefinedFunction,
  UserDefinedFunctionDefinition,
  UserDefinedType,
  UserDefinedTypeAlteration,
  UserDefinedTypeDefinition,
} from './schemaTypes';

const e = encodeURIComponent;

function base(connectionId: string): string {
  return `/api/connections/${e(connectionId)}`;
}

function keyspacePath(connectionId: string, keyspace: string): string {
  return `${base(connectionId)}/keyspaces/${e(keyspace)}`;
}

function tablePath(connectionId: string, keyspace: string, table: string): string {
  return `${keyspacePath(connectionId, keyspace)}/tables/${e(table)}`;
}

/* --------------------------------------------------------------------- tree and search */

export function getSchemaTree(
  connectionId: string,
  includeSystem = false,
  client: ApiClient = apiClient,
): Promise<ApiSchemaTree> {
  return client.get<ApiSchemaTree>(`${base(connectionId)}/schema/tree`, {
    query: { includeSystem },
  });
}

export interface SchemaSearchOptions {
  kinds?: string[];
  includeSystem?: boolean;
  limit?: number;
}

export function searchSchema(
  connectionId: string,
  q: string,
  options: SchemaSearchOptions = {},
  client: ApiClient = apiClient,
): Promise<ApiSchemaSearchResult> {
  return client.get<ApiSchemaSearchResult>(`${base(connectionId)}/schema/search`, {
    query: {
      q,
      kinds: options.kinds?.length ? options.kinds.join(',') : undefined,
      includeSystem: options.includeSystem ?? false,
      limit: options.limit ?? 100,
    },
  });
}

/* ------------------------------------------------------------------------- keyspaces */

export function listKeyspaces(
  connectionId: string,
  includeSystem = false,
  client: ApiClient = apiClient,
): Promise<Keyspace[]> {
  return client.get<Keyspace[]>(`${base(connectionId)}/keyspaces`, { query: { includeSystem } });
}

export function createKeyspace(
  connectionId: string,
  definition: KeyspaceDefinition,
  client: ApiClient = apiClient,
): Promise<DdlExecutionResult> {
  return client.post<DdlExecutionResult>(`${base(connectionId)}/keyspaces`, definition);
}

export function getKeyspace(
  connectionId: string,
  keyspace: string,
  client: ApiClient = apiClient,
): Promise<Keyspace> {
  return client.get<Keyspace>(keyspacePath(connectionId, keyspace));
}

export function alterKeyspace(
  connectionId: string,
  keyspace: string,
  definition: KeyspaceDefinition,
  client: ApiClient = apiClient,
): Promise<DdlExecutionResult> {
  return client.put<DdlExecutionResult>(keyspacePath(connectionId, keyspace), definition);
}

export function dropKeyspace(
  connectionId: string,
  keyspace: string,
  ifExists = true,
  client: ApiClient = apiClient,
): Promise<DdlExecutionResult> {
  return client.request<DdlExecutionResult>(keyspacePath(connectionId, keyspace), {
    method: 'DELETE',
    query: { ifExists },
  });
}

/* ---------------------------------------------------------------------------- tables */

export function listTables(
  connectionId: string,
  keyspace: string,
  client: ApiClient = apiClient,
): Promise<Table[]> {
  return client.get<Table[]>(`${keyspacePath(connectionId, keyspace)}/tables`);
}

export function createTable(
  connectionId: string,
  keyspace: string,
  definition: TableDefinition,
  client: ApiClient = apiClient,
): Promise<DdlExecutionResult> {
  return client.post<DdlExecutionResult>(
    `${keyspacePath(connectionId, keyspace)}/tables`,
    definition,
  );
}

export function getTable(
  connectionId: string,
  keyspace: string,
  table: string,
  client: ApiClient = apiClient,
): Promise<Table> {
  return client.get<Table>(tablePath(connectionId, keyspace, table));
}

export function alterTable(
  connectionId: string,
  keyspace: string,
  table: string,
  options: TableOptions,
  client: ApiClient = apiClient,
): Promise<DdlExecutionResult> {
  return client.put<DdlExecutionResult>(tablePath(connectionId, keyspace, table), options);
}

export function dropTable(
  connectionId: string,
  keyspace: string,
  table: string,
  ifExists = true,
  client: ApiClient = apiClient,
): Promise<DdlExecutionResult> {
  return client.request<DdlExecutionResult>(tablePath(connectionId, keyspace, table), {
    method: 'DELETE',
    query: { ifExists },
  });
}

export function truncateTable(
  connectionId: string,
  keyspace: string,
  table: string,
  client: ApiClient = apiClient,
): Promise<DdlExecutionResult> {
  return client.post<DdlExecutionResult>(
    `${tablePath(connectionId, keyspace, table)}/truncate`,
    {},
  );
}

export function getTableInfo(
  connectionId: string,
  keyspace: string,
  table: string,
  client: ApiClient = apiClient,
): Promise<TableInfo> {
  return client.get<TableInfo>(`${tablePath(connectionId, keyspace, table)}/info`);
}

export function updateTableComment(
  connectionId: string,
  keyspace: string,
  table: string,
  update: TableCommentUpdate,
  client: ApiClient = apiClient,
): Promise<DdlExecutionResult> {
  return client.put<DdlExecutionResult>(
    `${tablePath(connectionId, keyspace, table)}/comment`,
    update,
  );
}

/** 404 until a COUNT job has run — the UI turns that into an offer to start one (plan §5.4). */
export function getTableStatistics(
  connectionId: string,
  keyspace: string,
  table: string,
  client: ApiClient = apiClient,
): Promise<TableStatistics> {
  return client.get<TableStatistics>(`${tablePath(connectionId, keyspace, table)}/statistics`);
}

/* --------------------------------------------------------------------------- columns */

export function listColumns(
  connectionId: string,
  keyspace: string,
  table: string,
  client: ApiClient = apiClient,
): Promise<Column[]> {
  return client.get<Column[]>(`${tablePath(connectionId, keyspace, table)}/columns`);
}

export function addColumn(
  connectionId: string,
  keyspace: string,
  table: string,
  definition: ColumnDefinition,
  client: ApiClient = apiClient,
): Promise<DdlExecutionResult> {
  return client.post<DdlExecutionResult>(
    `${tablePath(connectionId, keyspace, table)}/columns`,
    definition,
  );
}

export function alterColumn(
  connectionId: string,
  keyspace: string,
  table: string,
  column: string,
  alteration: ColumnAlteration,
  client: ApiClient = apiClient,
): Promise<DdlExecutionResult> {
  return client.request<DdlExecutionResult>(
    `${tablePath(connectionId, keyspace, table)}/columns/${e(column)}`,
    { method: 'PATCH', body: alteration },
  );
}

export function dropColumn(
  connectionId: string,
  keyspace: string,
  table: string,
  column: string,
  client: ApiClient = apiClient,
): Promise<DdlExecutionResult> {
  return client.delete<DdlExecutionResult>(
    `${tablePath(connectionId, keyspace, table)}/columns/${e(column)}`,
  );
}

/* --------------------------------------------------------------------------- indexes */

export function listIndexes(
  connectionId: string,
  keyspace: string,
  table: string,
  client: ApiClient = apiClient,
): Promise<Index[]> {
  return client.get<Index[]>(`${tablePath(connectionId, keyspace, table)}/indexes`);
}

export function createIndex(
  connectionId: string,
  keyspace: string,
  table: string,
  definition: IndexDefinition,
  client: ApiClient = apiClient,
): Promise<DdlExecutionResult> {
  return client.post<DdlExecutionResult>(
    `${tablePath(connectionId, keyspace, table)}/indexes`,
    definition,
  );
}

export function dropIndex(
  connectionId: string,
  keyspace: string,
  table: string,
  index: string,
  ifExists = true,
  client: ApiClient = apiClient,
): Promise<DdlExecutionResult> {
  return client.request<DdlExecutionResult>(
    `${tablePath(connectionId, keyspace, table)}/indexes/${e(index)}`,
    { method: 'DELETE', query: { ifExists } },
  );
}

/* ---------------------------------------------------------------- materialized views */

export function listMaterializedViews(
  connectionId: string,
  keyspace: string,
  client: ApiClient = apiClient,
): Promise<MaterializedView[]> {
  return client.get<MaterializedView[]>(`${keyspacePath(connectionId, keyspace)}/views`);
}

export function createMaterializedView(
  connectionId: string,
  keyspace: string,
  definition: MaterializedViewDefinition,
  client: ApiClient = apiClient,
): Promise<DdlExecutionResult> {
  return client.post<DdlExecutionResult>(
    `${keyspacePath(connectionId, keyspace)}/views`,
    definition,
  );
}

export function getMaterializedView(
  connectionId: string,
  keyspace: string,
  view: string,
  client: ApiClient = apiClient,
): Promise<MaterializedView> {
  return client.get<MaterializedView>(`${keyspacePath(connectionId, keyspace)}/views/${e(view)}`);
}

export function alterMaterializedView(
  connectionId: string,
  keyspace: string,
  view: string,
  options: TableOptions,
  client: ApiClient = apiClient,
): Promise<DdlExecutionResult> {
  return client.put<DdlExecutionResult>(
    `${keyspacePath(connectionId, keyspace)}/views/${e(view)}`,
    options,
  );
}

export function dropMaterializedView(
  connectionId: string,
  keyspace: string,
  view: string,
  ifExists = true,
  client: ApiClient = apiClient,
): Promise<DdlExecutionResult> {
  return client.request<DdlExecutionResult>(
    `${keyspacePath(connectionId, keyspace)}/views/${e(view)}`,
    { method: 'DELETE', query: { ifExists } },
  );
}

/* ------------------------------------------------------------------------------ UDTs */

export function listUserDefinedTypes(
  connectionId: string,
  keyspace: string,
  client: ApiClient = apiClient,
): Promise<UserDefinedType[]> {
  return client.get<UserDefinedType[]>(`${keyspacePath(connectionId, keyspace)}/types`);
}

export function createUserDefinedType(
  connectionId: string,
  keyspace: string,
  definition: UserDefinedTypeDefinition,
  client: ApiClient = apiClient,
): Promise<DdlExecutionResult> {
  return client.post<DdlExecutionResult>(
    `${keyspacePath(connectionId, keyspace)}/types`,
    definition,
  );
}

export function getUserDefinedType(
  connectionId: string,
  keyspace: string,
  type: string,
  client: ApiClient = apiClient,
): Promise<UserDefinedType> {
  return client.get<UserDefinedType>(`${keyspacePath(connectionId, keyspace)}/types/${e(type)}`);
}

export function alterUserDefinedType(
  connectionId: string,
  keyspace: string,
  type: string,
  alteration: UserDefinedTypeAlteration,
  client: ApiClient = apiClient,
): Promise<DdlExecutionResult> {
  return client.put<DdlExecutionResult>(
    `${keyspacePath(connectionId, keyspace)}/types/${e(type)}`,
    alteration,
  );
}

export function dropUserDefinedType(
  connectionId: string,
  keyspace: string,
  type: string,
  ifExists = true,
  client: ApiClient = apiClient,
): Promise<DdlExecutionResult> {
  return client.request<DdlExecutionResult>(
    `${keyspacePath(connectionId, keyspace)}/types/${e(type)}`,
    { method: 'DELETE', query: { ifExists } },
  );
}

/* ---------------------------------------------------------------------- UDFs and UDAs */

export function listFunctions(
  connectionId: string,
  keyspace: string,
  client: ApiClient = apiClient,
): Promise<UserDefinedFunction[]> {
  return client.get<UserDefinedFunction[]>(`${keyspacePath(connectionId, keyspace)}/functions`);
}

export function createFunction(
  connectionId: string,
  keyspace: string,
  definition: UserDefinedFunctionDefinition,
  client: ApiClient = apiClient,
): Promise<DdlExecutionResult> {
  return client.post<DdlExecutionResult>(
    `${keyspacePath(connectionId, keyspace)}/functions`,
    definition,
  );
}

export function getFunction(
  connectionId: string,
  keyspace: string,
  functionSignature: string,
  client: ApiClient = apiClient,
): Promise<UserDefinedFunction> {
  return client.get<UserDefinedFunction>(
    `${keyspacePath(connectionId, keyspace)}/functions/${e(functionSignature)}`,
  );
}

export function dropFunction(
  connectionId: string,
  keyspace: string,
  functionSignature: string,
  ifExists = true,
  client: ApiClient = apiClient,
): Promise<DdlExecutionResult> {
  return client.request<DdlExecutionResult>(
    `${keyspacePath(connectionId, keyspace)}/functions/${e(functionSignature)}`,
    { method: 'DELETE', query: { ifExists } },
  );
}

export function listAggregates(
  connectionId: string,
  keyspace: string,
  client: ApiClient = apiClient,
): Promise<UserDefinedAggregate[]> {
  return client.get<UserDefinedAggregate[]>(`${keyspacePath(connectionId, keyspace)}/aggregates`);
}

export function createAggregate(
  connectionId: string,
  keyspace: string,
  definition: UserDefinedAggregateDefinition,
  client: ApiClient = apiClient,
): Promise<DdlExecutionResult> {
  return client.post<DdlExecutionResult>(
    `${keyspacePath(connectionId, keyspace)}/aggregates`,
    definition,
  );
}

export function getAggregate(
  connectionId: string,
  keyspace: string,
  aggregateSignature: string,
  client: ApiClient = apiClient,
): Promise<UserDefinedAggregate> {
  return client.get<UserDefinedAggregate>(
    `${keyspacePath(connectionId, keyspace)}/aggregates/${e(aggregateSignature)}`,
  );
}

export function dropAggregate(
  connectionId: string,
  keyspace: string,
  aggregateSignature: string,
  ifExists = true,
  client: ApiClient = apiClient,
): Promise<DdlExecutionResult> {
  return client.request<DdlExecutionResult>(
    `${keyspacePath(connectionId, keyspace)}/aggregates/${e(aggregateSignature)}`,
    { method: 'DELETE', query: { ifExists } },
  );
}

/* ------------------------------------------------------------------ roles and permissions */

export function listRoles(connectionId: string, client: ApiClient = apiClient): Promise<Role[]> {
  return client.get<Role[]>(`${base(connectionId)}/roles`);
}

export function createRole(
  connectionId: string,
  definition: RoleDefinition,
  client: ApiClient = apiClient,
): Promise<DdlExecutionResult> {
  return client.post<DdlExecutionResult>(`${base(connectionId)}/roles`, definition);
}

export function alterRole(
  connectionId: string,
  role: string,
  definition: RoleDefinition,
  client: ApiClient = apiClient,
): Promise<DdlExecutionResult> {
  return client.put<DdlExecutionResult>(`${base(connectionId)}/roles/${e(role)}`, definition);
}

export function dropRole(
  connectionId: string,
  role: string,
  ifExists = true,
  client: ApiClient = apiClient,
): Promise<DdlExecutionResult> {
  return client.request<DdlExecutionResult>(`${base(connectionId)}/roles/${e(role)}`, {
    method: 'DELETE',
    query: { ifExists },
  });
}

export function listPermissions(
  connectionId: string,
  filters: { role?: string; resource?: string } = {},
  client: ApiClient = apiClient,
): Promise<PermissionGrant[]> {
  return client.get<PermissionGrant[]>(`${base(connectionId)}/permissions`, {
    query: { role: filters.role, resource: filters.resource },
  });
}

export function grantPermission(
  connectionId: string,
  change: PermissionChangeRequest,
  client: ApiClient = apiClient,
): Promise<DdlExecutionResult> {
  return client.post<DdlExecutionResult>(`${base(connectionId)}/permissions/grant`, change);
}

export function revokePermission(
  connectionId: string,
  change: PermissionChangeRequest,
  client: ApiClient = apiClient,
): Promise<DdlExecutionResult> {
  return client.post<DdlExecutionResult>(`${base(connectionId)}/permissions/revoke`, change);
}

/* --------------------------------------------------------------- generate / preview / execute */

/** Pure server-side generation — never touches the cluster. Powers every Preview CQL pane. */
export function generateDdl(
  connectionId: string,
  request: DdlGenerateRequest,
  client: ApiClient = apiClient,
): Promise<DdlPreview> {
  return client.post<DdlPreview>(`${base(connectionId)}/ddl/generate`, request);
}

/** `describe` of an object that already exists. */
export function previewDdl(
  connectionId: string,
  request: DdlDescribeRequest,
  client: ApiClient = apiClient,
): Promise<DdlPreview> {
  return client.post<DdlPreview>(`${base(connectionId)}/ddl/preview`, request);
}

/** Runs CQL the user has seen and may have edited. Never called with un-previewed DDL. */
export function executeDdl(
  connectionId: string,
  request: DdlExecuteRequest,
  client: ApiClient = apiClient,
): Promise<DdlExecutionResult> {
  return client.post<DdlExecutionResult>(`${base(connectionId)}/ddl/execute`, request);
}
