/**
 * Typed call sites for the `query` and `data` tags of `openapi/cassyx-api.yaml`.
 *
 * Paging is server-side: the client holds a `resultHandle` and opaque page tokens and never
 * constructs, decodes or persists one. That is the whole point of plan §5.1 — the prior-art
 * prototype had no cursor handling at all and capped every result at `LIMIT 100`.
 */
import { apiClient, type ApiClient } from '../api/client';
import type {
  BatchRequestInput,
  BatchResult,
  CqlLexResult,
  CqlScriptSplitResult,
  QueryCancellationResult,
  QueryHistoryPage,
  QueryRequestInput,
  QueryResult,
  QueryTrace,
  ResultSetState,
  RowDeleteInput,
  RowEditabilityResult,
  RowInsertInput,
  RowMutationResult,
  RowStatementGenerationInput,
  RowStatementGenerationResult,
  RowUpdateInput,
  SavedScript,
  SavedScriptInput,
} from './types';

/**
 * Cancellation needs an id the client already knows. If the id only came back in the response, a
 * client could never cancel the query it is still waiting for — which is the only query anyone ever
 * wants to cancel. The server honours this header and echoes it as `queryId`.
 */
export const QUERY_ID_HEADER = 'X-Cassyx-Query-Id';

const enc = encodeURIComponent;

function rowsPath(connectionId: string, keyspace: string, table: string): string {
  return `/api/connections/${enc(connectionId)}/keyspaces/${enc(keyspace)}/tables/${enc(table)}/rows`;
}

export interface ExecuteQueryOptions {
  /** Client-generated execution id, so the query is cancellable while it is still running. */
  queryId?: string;
  signal?: AbortSignal;
  /** Long-running statements need more than the default client timeout. */
  timeoutMs?: number;
}

export function executeQuery(
  connectionId: string,
  request: QueryRequestInput,
  options: ExecuteQueryOptions = {},
  client: ApiClient = apiClient,
): Promise<QueryResult> {
  return client.post<QueryResult>(`/api/connections/${enc(connectionId)}/query`, request, {
    headers: options.queryId ? { [QUERY_ID_HEADER]: options.queryId } : undefined,
    signal: options.signal,
    timeoutMs: options.timeoutMs,
  });
}

export function executeBatch(
  connectionId: string,
  request: BatchRequestInput,
  client: ApiClient = apiClient,
): Promise<BatchResult> {
  return client.post<BatchResult>(`/api/connections/${enc(connectionId)}/query/batch`, request);
}

export function fetchNextPage(
  resultHandle: string,
  pageToken: string,
  fetchSize?: number,
  client: ApiClient = apiClient,
): Promise<QueryResult> {
  return client.post<QueryResult>(`/api/query/results/${enc(resultHandle)}/next-page`, {
    pageToken,
    ...(fetchSize ? { fetchSize } : {}),
  });
}

/**
 * Cassandra's paging state is forward-only, so this replays a token the SERVER retained on the way
 * forward. There is no offset to fall back on — CQL has none.
 */
export function fetchPreviousPage(
  resultHandle: string,
  pageToken: string,
  fetchSize?: number,
  client: ApiClient = apiClient,
): Promise<QueryResult> {
  return client.post<QueryResult>(`/api/query/results/${enc(resultHandle)}/previous-page`, {
    pageToken,
    ...(fetchSize ? { fetchSize } : {}),
  });
}

export function getResultSetState(
  resultHandle: string,
  client: ApiClient = apiClient,
): Promise<ResultSetState> {
  return client.get<ResultSetState>(`/api/query/results/${enc(resultHandle)}`);
}

export function closeResultSet(resultHandle: string, client: ApiClient = apiClient): Promise<void> {
  return client.delete<void>(`/api/query/results/${enc(resultHandle)}`);
}

export function cancelQuery(
  queryId: string,
  client: ApiClient = apiClient,
): Promise<QueryCancellationResult> {
  return client.post<QueryCancellationResult>(`/api/query/executions/${enc(queryId)}/cancel`);
}

export function getQueryTrace(queryId: string, client: ApiClient = apiClient): Promise<QueryTrace> {
  return client.get<QueryTrace>(`/api/query/executions/${enc(queryId)}/trace`);
}

export function splitCqlScript(
  cql: string,
  cursorOffset?: number,
  client: ApiClient = apiClient,
): Promise<CqlScriptSplitResult> {
  return client.post<CqlScriptSplitResult>('/api/query/script/split', {
    cql,
    ...(cursorOffset === undefined ? {} : { cursorOffset }),
  });
}

export function lexCqlScript(cql: string, client: ApiClient = apiClient): Promise<CqlLexResult> {
  return client.post<CqlLexResult>('/api/query/script/lex', { cql });
}

/* --------------------------------------------------------------------- history & scripts */

export interface QueryHistoryFilter {
  connectionId?: string;
  q?: string;
  limit?: number;
  offset?: number;
}

export function listQueryHistory(
  filter: QueryHistoryFilter = {},
  client: ApiClient = apiClient,
): Promise<QueryHistoryPage> {
  return client.get<QueryHistoryPage>('/api/query/history', { query: { ...filter } });
}

export function clearQueryHistory(
  connectionId?: string,
  client: ApiClient = apiClient,
): Promise<void> {
  return client.delete<void>('/api/query/history', { query: { connectionId } });
}

export function listSavedScripts(
  folder?: string,
  client: ApiClient = apiClient,
): Promise<SavedScript[]> {
  return client.get<SavedScript[]>('/api/query/scripts', { query: { folder } });
}

export function createSavedScript(
  request: SavedScriptInput,
  client: ApiClient = apiClient,
): Promise<SavedScript> {
  return client.post<SavedScript>('/api/query/scripts', request);
}

export function updateSavedScript(
  scriptId: string,
  request: SavedScriptInput,
  client: ApiClient = apiClient,
): Promise<SavedScript> {
  return client.put<SavedScript>(`/api/query/scripts/${enc(scriptId)}`, request);
}

export function deleteSavedScript(scriptId: string, client: ApiClient = apiClient): Promise<void> {
  return client.delete<void>(`/api/query/scripts/${enc(scriptId)}`);
}

/* ------------------------------------------------------------------------- data tag (§7) */

export function insertRow(
  connectionId: string,
  keyspace: string,
  table: string,
  request: RowInsertInput,
  client: ApiClient = apiClient,
): Promise<RowMutationResult> {
  return client.post<RowMutationResult>(rowsPath(connectionId, keyspace, table), request);
}

export function updateRow(
  connectionId: string,
  keyspace: string,
  table: string,
  request: RowUpdateInput,
  client: ApiClient = apiClient,
): Promise<RowMutationResult> {
  return client.request<RowMutationResult>(rowsPath(connectionId, keyspace, table), {
    method: 'PATCH',
    body: request,
  });
}

export function deleteRow(
  connectionId: string,
  keyspace: string,
  table: string,
  request: RowDeleteInput,
  client: ApiClient = apiClient,
): Promise<RowMutationResult> {
  return client.request<RowMutationResult>(rowsPath(connectionId, keyspace, table), {
    method: 'DELETE',
    body: request,
  });
}

export function generateRowStatements(
  connectionId: string,
  keyspace: string,
  table: string,
  request: RowStatementGenerationInput,
  client: ApiClient = apiClient,
): Promise<RowStatementGenerationResult> {
  return client.post<RowStatementGenerationResult>(
    `${rowsPath(connectionId, keyspace, table)}/statements`,
    request,
  );
}

/**
 * The grid calls this once per result set and shows `reason` verbatim. Refusing to edit without
 * saying why is the behaviour plan §7 singles out as the thing to fix.
 */
export function checkRowEditability(
  connectionId: string,
  keyspace: string,
  table: string,
  projectedColumns: string[],
  resultHandle?: string,
  client: ApiClient = apiClient,
): Promise<RowEditabilityResult> {
  return client.post<RowEditabilityResult>(
    `${rowsPath(connectionId, keyspace, table)}/editability`,
    { projectedColumns, ...(resultHandle ? { resultHandle } : {}) },
  );
}
