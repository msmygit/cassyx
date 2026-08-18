/**
 * Typed call sites for the `vector` tag of `openapi/cassyx-api.yaml` (plan §6).
 *
 * Feature endpoints live with their feature, not in `src/api/endpoints.ts` — that file stays small
 * on purpose (see its header).
 */
import { apiClient, type ApiClient } from '../api/client';
import type {
  AnnQueryPreview,
  AnnQueryRequest,
  DdlExecutionResult,
  QueryResult,
  SaiIndex,
  SaiIndexDefinition,
  SaiIndexStatus,
  SimilarityRequest,
  SimilarityResult,
  VectorColumn,
  VectorColumnDefinition,
} from './types';

const enc = encodeURIComponent;

function tablePath(connectionId: string, keyspace: string, table: string): string {
  return `/api/connections/${enc(connectionId)}/keyspaces/${enc(keyspace)}/tables/${enc(table)}`;
}

/* -------------------------------------------------------------- vector columns */

export function listVectorColumns(
  connectionId: string,
  keyspace: string,
  table: string,
  client: ApiClient = apiClient,
): Promise<VectorColumn[]> {
  return client.get<VectorColumn[]>(`${tablePath(connectionId, keyspace, table)}/vector-columns`);
}

export function addVectorColumn(
  connectionId: string,
  keyspace: string,
  table: string,
  definition: VectorColumnDefinition,
  client: ApiClient = apiClient,
): Promise<DdlExecutionResult> {
  return client.post<DdlExecutionResult>(
    `${tablePath(connectionId, keyspace, table)}/vector-columns`,
    definition,
  );
}

/* ------------------------------------------------------------------ SAI indexes */

export function listSaiIndexes(
  connectionId: string,
  keyspace: string,
  table: string,
  client: ApiClient = apiClient,
): Promise<SaiIndex[]> {
  return client.get<SaiIndex[]>(`${tablePath(connectionId, keyspace, table)}/sai-indexes`);
}

export function createSaiIndex(
  connectionId: string,
  keyspace: string,
  table: string,
  definition: SaiIndexDefinition,
  client: ApiClient = apiClient,
): Promise<DdlExecutionResult> {
  return client.post<DdlExecutionResult>(
    `${tablePath(connectionId, keyspace, table)}/sai-indexes`,
    definition,
  );
}

export function getSaiIndexStatus(
  connectionId: string,
  keyspace: string,
  table: string,
  index: string,
  client: ApiClient = apiClient,
): Promise<SaiIndexStatus> {
  return client.get<SaiIndexStatus>(
    `${tablePath(connectionId, keyspace, table)}/sai-indexes/${enc(index)}`,
  );
}

/** Cassandra has no `ALTER INDEX`; the server runs a drop-and-recreate pair and returns both. */
export function alterSaiIndex(
  connectionId: string,
  keyspace: string,
  table: string,
  index: string,
  definition: SaiIndexDefinition,
  client: ApiClient = apiClient,
): Promise<DdlExecutionResult> {
  return client.put<DdlExecutionResult>(
    `${tablePath(connectionId, keyspace, table)}/sai-indexes/${enc(index)}`,
    definition,
  );
}

export function dropSaiIndex(
  connectionId: string,
  keyspace: string,
  table: string,
  index: string,
  ifExists = true,
  client: ApiClient = apiClient,
): Promise<DdlExecutionResult> {
  return client.delete<DdlExecutionResult>(
    `${tablePath(connectionId, keyspace, table)}/sai-indexes/${enc(index)}`,
    { query: { ifExists } },
  );
}

/* -------------------------------------------------------------------- ANN query */

/** Generate only — nothing is executed, and the result is editable in the preview pane. */
export function buildAnnQuery(
  connectionId: string,
  request: AnnQueryRequest,
  client: ApiClient = apiClient,
): Promise<AnnQueryPreview> {
  return client.post<AnnQueryPreview>(
    `/api/connections/${enc(connectionId)}/vector/ann-query`,
    request,
  );
}

export function executeAnnQuery(
  connectionId: string,
  request: AnnQueryRequest,
  client: ApiClient = apiClient,
): Promise<QueryResult> {
  return client.post<QueryResult>(
    `/api/connections/${enc(connectionId)}/vector/ann-query/execute`,
    request,
  );
}

/**
 * Inspector arithmetic, computed server-side so the browser never ships large float arrays and the
 * numbers match the cluster's `similarity_*` functions exactly.
 */
export function computeSimilarity(
  connectionId: string,
  request: SimilarityRequest,
  client: ApiClient = apiClient,
): Promise<SimilarityResult> {
  return client.post<SimilarityResult>(
    `/api/connections/${enc(connectionId)}/vector/similarity`,
    request,
  );
}

/** Query keys for TanStack Query, so cache invalidation after DDL is not guesswork. */
export const vectorQueryKeys = {
  vectorColumns: (connectionId: string, keyspace: string, table: string) =>
    ['vector', 'columns', connectionId, keyspace, table] as const,
  saiIndexes: (connectionId: string, keyspace: string, table: string) =>
    ['vector', 'sai-indexes', connectionId, keyspace, table] as const,
  saiIndexStatus: (connectionId: string, keyspace: string, table: string, index: string) =>
    ['vector', 'sai-index-status', connectionId, keyspace, table, index] as const,
};
