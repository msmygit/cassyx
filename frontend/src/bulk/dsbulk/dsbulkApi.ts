/**
 * Typed call sites for the DSBulk / bulk endpoints (plan §5.3, §5.4).
 *
 * Feature endpoints live with their feature (see the note at the top of `src/api/endpoints.ts`),
 * so these are here rather than in the shell's endpoint module. Every function takes an injectable
 * `client` so tests can pass an `ApiClient` with a stubbed `fetchImpl`.
 *
 * SECURITY: nothing here ever puts a credential in a path or query string. S3 keys travel inside
 * the JSON body only, and the server masks them again in the command preview it returns.
 */
import { apiClient, type ApiClient } from '../../api/client';
import type { Schemas } from '../../api/types';

export type BulkDefaultsRequest = Schemas['BulkDefaultsRequest'];
export type DerivedSettingsResponse = Schemas['DerivedSettingsResponse'];
export type BulkCommandPreviewRequest = Schemas['BulkCommandPreviewRequest'];
export type BulkCommandPreview = Schemas['BulkCommandPreview'];
export type BulkUpload = Schemas['BulkUpload'];
export type LoadJobRequest = Schemas['LoadJobRequest'];
export type CountJobRequest = Schemas['CountJobRequest'];
export type ExportFormat = Schemas['ExportFormat'];
export type Job = Schemas['Job'];
export type JobTemplate = Schemas['JobTemplate'];
export type JobTemplateRequest = Schemas['JobTemplateRequest'];

/** Staged uploads can be very large; six hours rather than the 30s default. */
export const UPLOAD_TIMEOUT_MS = 6 * 60 * 60 * 1000;

function connectionPath(connectionId: string, suffix: string): string {
  return `/api/connections/${encodeURIComponent(connectionId)}${suffix}`;
}

/** `POST /api/connections/{connectionId}/bulk/defaults` — the auto-chip source. */
export function deriveBulkDefaults(
  connectionId: string,
  request: BulkDefaultsRequest,
  client: ApiClient = apiClient,
): Promise<DerivedSettingsResponse> {
  return client.post<DerivedSettingsResponse>(
    connectionPath(connectionId, '/bulk/defaults'),
    request,
  );
}

/** `POST /api/connections/{connectionId}/bulk/command-preview` — command, argv and HOCON. */
export function previewBulkCommand(
  connectionId: string,
  request: BulkCommandPreviewRequest,
  client: ApiClient = apiClient,
): Promise<BulkCommandPreview> {
  return client.post<BulkCommandPreview>(
    connectionPath(connectionId, '/bulk/command-preview'),
    request,
  );
}

/** `POST /api/connections/{connectionId}/jobs/load` — 202 with the queued `Job`. */
export function createLoadJob(
  connectionId: string,
  request: LoadJobRequest,
  client: ApiClient = apiClient,
): Promise<Job> {
  return client.post<Job>(connectionPath(connectionId, '/jobs/load'), request);
}

/** `POST /api/connections/{connectionId}/jobs/count` — the DSBulk `count` workflow (§5.4). */
export function createCountJob(
  connectionId: string,
  request: CountJobRequest,
  client: ApiClient = apiClient,
): Promise<Job> {
  return client.post<Job>(connectionPath(connectionId, '/jobs/count'), request);
}

/**
 * `POST /api/bulk/uploads` — stage a source file and get back the handle referenced by
 * `LoadJobRequest.source.uploadId`. Streamed as multipart; the browser never buffers the bytes.
 */
export function uploadBulkSourceFile(
  file: File,
  format?: ExportFormat,
  client: ApiClient = apiClient,
): Promise<BulkUpload> {
  const form = new FormData();
  form.append('file', file);
  if (format) form.append('format', format);
  // A multi-gigabyte upload must not be aborted by the 30s default request timeout.
  return client.upload<BulkUpload>('/api/bulk/uploads', form, { timeoutMs: UPLOAD_TIMEOUT_MS });
}

/* --------------------------------------------------------------------------- job templates */

export function listJobTemplates(client: ApiClient = apiClient): Promise<JobTemplate[]> {
  return client.get<JobTemplate[]>('/api/job-templates');
}

export function createJobTemplate(
  request: JobTemplateRequest,
  client: ApiClient = apiClient,
): Promise<JobTemplate> {
  return client.post<JobTemplate>('/api/job-templates', request);
}

export function updateJobTemplate(
  templateId: string,
  request: JobTemplateRequest,
  client: ApiClient = apiClient,
): Promise<JobTemplate> {
  return client.put<JobTemplate>(`/api/job-templates/${encodeURIComponent(templateId)}`, request);
}

export function deleteJobTemplate(
  templateId: string,
  client: ApiClient = apiClient,
): Promise<void> {
  return client.delete<void>(`/api/job-templates/${encodeURIComponent(templateId)}`);
}

/**
 * Transport seam, mirroring `connections/astraApi.ts`: components take this instead of importing
 * the functions directly, so tests and the parent workstream can swap the implementation.
 */
export interface DsbulkApi {
  deriveDefaults: (
    connectionId: string,
    request: BulkDefaultsRequest,
  ) => Promise<DerivedSettingsResponse>;
  previewCommand: (
    connectionId: string,
    request: BulkCommandPreviewRequest,
  ) => Promise<BulkCommandPreview>;
  createLoadJob: (connectionId: string, request: LoadJobRequest) => Promise<Job>;
  createCountJob: (connectionId: string, request: CountJobRequest) => Promise<Job>;
  uploadSourceFile: (file: File, format?: ExportFormat) => Promise<BulkUpload>;
}

export const defaultDsbulkApi: DsbulkApi = {
  deriveDefaults: (connectionId, request) => deriveBulkDefaults(connectionId, request),
  previewCommand: (connectionId, request) => previewBulkCommand(connectionId, request),
  createLoadJob: (connectionId, request) => createLoadJob(connectionId, request),
  createCountJob: (connectionId, request) => createCountJob(connectionId, request),
  uploadSourceFile: (file, format) => uploadBulkSourceFile(file, format),
};
