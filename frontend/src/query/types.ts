/**
 * Query-engine types, aliased from the generated contract (`src/api/schema.d.ts`).
 *
 * Aliasing rather than re-declaring means a contract change breaks `tsc` here instead of drifting
 * silently into a runtime shape mismatch.
 */
import type { Schemas } from '../api/types';

export type QueryRequest = Schemas['QueryRequest'];
export type QueryResult = Schemas['QueryResult'];
export type ColumnMetadata = Schemas['ColumnMetadata'];
export type PageRequest = Schemas['PageRequest'];
export type ResultSetState = Schemas['ResultSetState'];
export type QueryCancellationResult = Schemas['QueryCancellationResult'];
export type QueryTrace = Schemas['QueryTrace'];
export type QueryTraceEvent = Schemas['QueryTraceEvent'];
export type BatchRequest = Schemas['BatchRequest'];
export type BatchResult = Schemas['BatchResult'];
export type BatchType = Schemas['BatchType'];
export type CqlScriptSplitRequest = Schemas['CqlScriptSplitRequest'];
export type CqlScriptSplitResult = Schemas['CqlScriptSplitResult'];
export type CqlStatementSlice = Schemas['CqlStatementSlice'];
export type CqlLexResult = Schemas['CqlLexResult'];
export type CqlToken = Schemas['CqlToken'];
export type QueryHistoryEntry = Schemas['QueryHistoryEntry'];
export type QueryHistoryPage = Schemas['QueryHistoryPage'];
export type SavedScript = Schemas['SavedScript'];
export type SavedScriptRequest = Schemas['SavedScriptRequest'];
export type ConsistencyLevel = Schemas['ConsistencyLevel'];
export type SerialConsistencyLevel = Schemas['SerialConsistencyLevel'];

/* --------------------------------------------------------------------------- data tag (§7) */

export type RowInsertRequest = Schemas['RowInsertRequest'];
export type RowUpdateRequest = Schemas['RowUpdateRequest'];
export type RowDeleteRequest = Schemas['RowDeleteRequest'];
export type RowMutationResult = Schemas['RowMutationResult'];
export type RowStatementGenerationRequest = Schemas['RowStatementGenerationRequest'];
export type RowStatementGenerationResult = Schemas['RowStatementGenerationResult'];
export type RowEditabilityRequest = Schemas['RowEditabilityRequest'];
export type RowEditabilityResult = Schemas['RowEditabilityResult'];
export type StatementKind = Schemas['StatementKind'];

/**
 * Request shapes as CALLERS build them.
 *
 * `openapi-typescript` renders any property with a `default:` as required, because the server will
 * always send one back. That is right for responses and wrong for requests — the whole point of a
 * default is that the client may omit it. These aliases restore that, without weakening the
 * response types.
 */
export type QueryRequestInput = Partial<QueryRequest> & Pick<QueryRequest, 'cql'>;
export type BatchRequestInput = Partial<BatchRequest> & Pick<BatchRequest, 'type' | 'statements'>;
export type SavedScriptInput = Partial<SavedScriptRequest> &
  Pick<SavedScriptRequest, 'name' | 'cql'>;
export type RowInsertInput = Partial<RowInsertRequest> & Pick<RowInsertRequest, 'values'>;
export type RowUpdateInput = Partial<RowUpdateRequest> &
  Pick<RowUpdateRequest, 'primaryKey' | 'values'>;
export type RowDeleteInput = Partial<RowDeleteRequest> & Pick<RowDeleteRequest, 'primaryKey'>;
export type RowStatementGenerationInput = Partial<RowStatementGenerationRequest> &
  Pick<RowStatementGenerationRequest, 'statementKind' | 'rows'>;

/** One row of a result page: column name → wire-encoded value. */
export type ResultRow = Record<string, unknown>;

/**
 * The `"$unset"` sentinel of the contract's `CqlValue`.
 *
 * `null` writes a tombstone; *unset* does not write the column at all. Cassandra treats these
 * differently and every other GUI hides the difference — cassyx makes it visible (plan §7).
 */
export const UNSET = '$unset';

/** UI-only: which values a cell can be in the editor. */
export type CellState = 'value' | 'null' | 'unset';

export const CONSISTENCY_LEVELS: ConsistencyLevel[] = [
  'ANY',
  'ONE',
  'TWO',
  'THREE',
  'QUORUM',
  'ALL',
  'LOCAL_ONE',
  'LOCAL_QUORUM',
  'EACH_QUORUM',
];

export const SERIAL_CONSISTENCY_LEVELS: SerialConsistencyLevel[] = ['SERIAL', 'LOCAL_SERIAL'];

export const BATCH_TYPES: BatchType[] = ['LOGGED', 'UNLOGGED', 'COUNTER'];

/** Plan §5.1: fetch size default 500 — never a hardcoded LIMIT. */
export const DEFAULT_FETCH_SIZE = 500;
