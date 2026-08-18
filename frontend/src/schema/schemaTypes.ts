/**
 * Contract types for the schema workstream (plan §4), aliased from the generated client.
 *
 * Aliasing rather than re-declaring is deliberate: if `openapi/cassyx-api.yaml` changes shape the
 * build breaks at `tsc` time instead of drifting silently (plan §2.3).
 */
import type { Schemas } from '../api/types';

/**
 * `openapi-typescript` marks a property with a `default` as REQUIRED, because the server will
 * always send one back. On the request side that is the wrong way round: a defaulted field is
 * exactly the one a caller may omit. This makes those fields optional again, explicitly, so the
 * set of "has a server-side default" fields is visible rather than lost in a blanket `Partial`.
 */
type Defaulted<T, K extends keyof T> = Omit<T, K> & Partial<Pick<T, K>>;

/* ------------------------------------------------------------------------ catalog reads */

export type ApiSchemaIdentity = Schemas['SchemaIdentity'];
export type ApiSchemaNode = Schemas['SchemaNode'];
export type ApiSchemaTree = Schemas['SchemaTree'];
export type ApiSchemaSearchResult = Schemas['SchemaSearchResult'];
export type ApiSchemaSearchMatch = Schemas['SchemaSearchMatch'];

export type Keyspace = Schemas['Keyspace'];
export type Table = Schemas['Table'];
export type Column = Schemas['Column'];
export type Index = Schemas['Index'];
export type MaterializedView = Schemas['MaterializedView'];
export type UserDefinedType = Schemas['UserDefinedType'];
export type UserDefinedFunction = Schemas['UserDefinedFunction'];
export type UserDefinedAggregate = Schemas['UserDefinedAggregate'];
export type Role = Schemas['Role'];
export type PermissionGrant = Schemas['PermissionGrant'];
export type TableInfo = Schemas['TableInfo'];
export type TableStatistics = Schemas['TableStatistics'];

/* ------------------------------------------------------------------------- DDL requests */

export type KeyspaceDefinition = Defaulted<
  Schemas['KeyspaceDefinition'],
  'durableWrites' | 'ifNotExists'
>;
export type ColumnDefinition = Defaulted<Schemas['ColumnDefinition'], 'static'>;
export type TableDefinition = Omit<
  Defaulted<Schemas['TableDefinition'], 'ifNotExists'>,
  'columns'
> & { columns: ColumnDefinition[] };
export type TableOptions = Schemas['TableOptions'];
export type TableCommentUpdate = Schemas['TableCommentUpdate'];
export type ColumnAlteration = Schemas['ColumnAlteration'];
export type IndexDefinition = Defaulted<Schemas['IndexDefinition'], 'ifNotExists'>;
export type IndexKind = Schemas['IndexKind'];
export type MaterializedViewDefinition = Defaulted<
  Schemas['MaterializedViewDefinition'],
  'selectedColumns' | 'ifNotExists'
>;
export type UserDefinedTypeDefinition = Defaulted<
  Schemas['UserDefinedTypeDefinition'],
  'ifNotExists'
>;
export type UserDefinedTypeAlteration = Schemas['UserDefinedTypeAlteration'];
export type UserDefinedFunctionDefinition = Defaulted<
  Schemas['UserDefinedFunctionDefinition'],
  'nullHandling' | 'orReplace' | 'ifNotExists'
>;
export type UserDefinedAggregateDefinition = Defaulted<
  Schemas['UserDefinedAggregateDefinition'],
  'orReplace' | 'ifNotExists'
>;
export type RoleDefinition = Defaulted<
  Schemas['RoleDefinition'],
  'superuser' | 'login' | 'ifNotExists'
>;
export type PermissionChangeRequest = Schemas['PermissionChangeRequest'];
export type PrimaryKeyDefinition = Schemas['PrimaryKeyDefinition'];
export type ReplicationSettings = Schemas['ReplicationSettings'];

/* ----------------------------------------------------------------- generate / preview */

export type DdlGenerateRequest = Schemas['DdlGenerateRequest'];
export type DdlDescribeRequest = Defaulted<
  Schemas['DdlDescribeRequest'],
  'withChildren' | 'formatted'
>;
export type DdlExecuteRequest = Defaulted<
  Schemas['DdlExecuteRequest'],
  'stopOnError' | 'awaitSchemaAgreement'
>;
export type DdlPreview = Schemas['DdlPreview'];
export type DdlExecutionResult = Schemas['DdlExecutionResult'];
export type DdlObjectType = Schemas['DdlObjectType'];
export type DdlAction = Schemas['DdlAction'];

export type CapabilityName = Schemas['CapabilityName'];
