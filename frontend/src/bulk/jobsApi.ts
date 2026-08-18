/**
 * Typed call sites for the job substrate (plan §5.5, OpenAPI tag `bulk`).
 *
 * Feature endpoints live with their feature (see the note at the top of `src/api/endpoints.ts`),
 * so these sit here rather than in the shell's endpoint module. Every function takes an injectable
 * `client` so tests can pass an `ApiClient` built over a stub `fetchImpl`.
 *
 * Every type is an alias over the GENERATED contract (`Schemas[...]`). Re-declaring a job shape by
 * hand would let the UI drift from `openapi/cassyx-api.yaml` silently instead of failing at `tsc`.
 */
import { apiClient, type ApiClient } from '../api/client';
import type { Schemas } from '../api/types';

export type Job = Schemas['Job'];
export type JobPage = Schemas['JobPage'];
export type JobStatus = Schemas['JobStatus'];
export type JobType = Schemas['JobType'];
export type JobProgress = Schemas['JobProgress'];
export type JobArtifact = Schemas['JobArtifact'];
export type JobLogPage = Schemas['JobLogPage'];
export type JobLogLine = Schemas['JobLogLine'];
export type UnloadJobRequest = Schemas['UnloadJobRequest'];
export type ExportFormat = Schemas['ExportFormat'];
export type UnloadSink = Schemas['UnloadSink'];
export type BulkEngine = Schemas['BulkEngine'];
export type LogLevel = Schemas['LogLevel'];

/** `GET /api/jobs` query parameters. */
export interface JobFilters {
  status?: JobStatus[];
  type?: JobType[];
  connectionId?: string;
  limit?: number;
  offset?: number;
}

export interface JobLogOptions {
  /** Last N lines. The contract's default is 1000, max 100000. */
  tail?: number;
  level?: LogLevel;
}

type Query = Record<string, string | number | boolean | undefined | null>;

/**
 * `status` and `type` are declared `style: form, explode: false` in the contract, i.e. one
 * comma-joined parameter rather than a repeated one.
 */
export function jobFiltersToQuery(filters: JobFilters = {}): Query {
  const query: Query = {};
  if (filters.status && filters.status.length > 0) query.status = filters.status.join(',');
  if (filters.type && filters.type.length > 0) query.type = filters.type.join(',');
  if (filters.connectionId) query.connectionId = filters.connectionId;
  if (filters.limit !== undefined) query.limit = filters.limit;
  if (filters.offset !== undefined) query.offset = filters.offset;
  return query;
}

function jobPath(jobId: string, suffix = ''): string {
  return `/api/jobs/${encodeURIComponent(jobId)}${suffix}`;
}

/** `GET /api/jobs` — newest first, paged. */
export function listJobs(
  filters: JobFilters = {},
  client: ApiClient = apiClient,
): Promise<JobPage> {
  return client.get<JobPage>('/api/jobs', { query: jobFiltersToQuery(filters) });
}

/** `GET /api/jobs/{jobId}` — the polling fallback for clients that cannot consume SSE. */
export function getJob(jobId: string, client: ApiClient = apiClient): Promise<Job> {
  return client.get<Job>(jobPath(jobId));
}

/** `POST /api/jobs/{jobId}/cancel` — 202; the `CANCELLED` transition arrives on the SSE stream. */
export function cancelJob(jobId: string, client: ApiClient = apiClient): Promise<Job> {
  return client.post<Job>(jobPath(jobId, '/cancel'));
}

/** `DELETE /api/jobs/{jobId}` — 204. 409 if the job is still `QUEUED`/`RUNNING`. */
export function deleteJob(jobId: string, client: ApiClient = apiClient): Promise<void> {
  return client.delete<void>(jobPath(jobId));
}

/** `GET /api/jobs/{jobId}/logs` — retained logs, JSON flavour. */
export function fetchJobLogs(
  jobId: string,
  options: JobLogOptions = {},
  client: ApiClient = apiClient,
): Promise<JobLogPage> {
  const query: Query = {};
  if (options.tail !== undefined) query.tail = options.tail;
  if (options.level !== undefined) query.level = options.level;
  return client.get<JobLogPage>(jobPath(jobId, '/logs'), { query });
}

/** `POST /api/connections/{connectionId}/jobs/unload` — 202 with the queued `Job`. */
export function createUnloadJob(
  connectionId: string,
  request: UnloadJobRequest,
  client: ApiClient = apiClient,
): Promise<Job> {
  return client.post<Job>(
    `/api/connections/${encodeURIComponent(connectionId)}/jobs/unload`,
    request,
  );
}

/**
 * URL for `GET /api/jobs/{jobId}/artifact`, for use as an `<a href download>` target.
 *
 * Deliberately returns a URL and never fetches: the artifact can be gigabytes and is streamed by
 * the server with `StreamingResponseBody`. Reading it through `fetch` would buffer the whole
 * payload in the tab's heap — bulk data must never round-trip through the browser (plan §2).
 */
export function jobArtifactUrl(
  jobId: string,
  artifactId?: string,
  client: ApiClient = apiClient,
): string {
  return client.url(jobPath(jobId, '/artifact'), artifactId ? { artifactId } : undefined);
}

/**
 * Transport seam, mirroring `bulk/dsbulk/dsbulkApi.ts`: hooks take this rather than importing the
 * functions directly, so tests and other workstreams can swap the implementation wholesale.
 */
export interface JobsApi {
  listJobs: (filters: JobFilters) => Promise<JobPage>;
  getJob: (jobId: string) => Promise<Job>;
  cancelJob: (jobId: string) => Promise<Job>;
  deleteJob: (jobId: string) => Promise<void>;
  fetchLogs: (jobId: string, options?: JobLogOptions) => Promise<JobLogPage>;
  createUnloadJob: (connectionId: string, request: UnloadJobRequest) => Promise<Job>;
  artifactUrl: (jobId: string, artifactId?: string) => string;
}

export const defaultJobsApi: JobsApi = {
  listJobs: (filters) => listJobs(filters),
  getJob: (jobId) => getJob(jobId),
  cancelJob: (jobId) => cancelJob(jobId),
  deleteJob: (jobId) => deleteJob(jobId),
  fetchLogs: (jobId, options) => fetchJobLogs(jobId, options),
  createUnloadJob: (connectionId, request) => createUnloadJob(connectionId, request),
  artifactUrl: (jobId, artifactId) => jobArtifactUrl(jobId, artifactId),
};
