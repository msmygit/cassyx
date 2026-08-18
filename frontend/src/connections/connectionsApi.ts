/**
 * Typed call sites for the `connections` and `capabilities` tags of the contract (plan §3, §7.1).
 *
 * Deliberately separate from `src/api/endpoints.ts`, which is only what the app *shell* needs:
 * feature endpoints belong next to their feature, so a workstream can move without touching a
 * shared file eight agents are editing.
 *
 * SECURITY: every credential travels in a request body or the `X-Astra-Token` header. Nothing here
 * ever puts one in a URL or a query string — those land in access logs, proxy logs, browser history
 * and `Referer` headers.
 */
import { apiClient, type ApiClient } from '../api/client';
import type { Schemas } from '../api/types';

export type ConnectionRequest = Schemas['ConnectionRequest'];
export type ConnectionResponse = Schemas['ConnectionResponse'];
export type ConnectionTestResult = Schemas['ConnectionTestResult'];
export type ConnectionHealth = Schemas['ConnectionHealth'];
export type SessionState = Schemas['SessionState'];
export type ClusterCapabilities = Schemas['ClusterCapabilities'];
export type SecureConnectBundleInfo = Schemas['SecureConnectBundleInfo'];

/* --------------------------------------------------------------------------------- CRUD */

export function listConnections(client: ApiClient = apiClient): Promise<ConnectionResponse[]> {
  return client.get<ConnectionResponse[]>('/api/connections');
}

export function getConnection(
  connectionId: string,
  client: ApiClient = apiClient,
): Promise<ConnectionResponse> {
  return client.get<ConnectionResponse>(`/api/connections/${encodeURIComponent(connectionId)}`);
}

export function createConnection(
  request: ConnectionRequest,
  client: ApiClient = apiClient,
): Promise<ConnectionResponse> {
  return client.post<ConnectionResponse>('/api/connections', request);
}

/**
 * Full replacement.
 *
 * A secret left `undefined`/`null` PRESERVES the stored value; an empty string CLEARS it. That
 * asymmetry is what lets this form be an edit form at all — the browser never receives the secret,
 * so it has nothing to send back.
 */
export function updateConnection(
  connectionId: string,
  request: ConnectionRequest,
  client: ApiClient = apiClient,
): Promise<ConnectionResponse> {
  return client.put<ConnectionResponse>(
    `/api/connections/${encodeURIComponent(connectionId)}`,
    request,
  );
}

export function deleteConnection(
  connectionId: string,
  client: ApiClient = apiClient,
): Promise<void> {
  return client.delete<void>(`/api/connections/${encodeURIComponent(connectionId)}`);
}

/* ----------------------------------------------------------------------------- sessions */

/** Idempotent: connecting an already-connected connection returns the existing session state. */
export function connectConnection(
  connectionId: string,
  client: ApiClient = apiClient,
): Promise<SessionState> {
  return client.post<SessionState>(
    `/api/connections/${encodeURIComponent(connectionId)}/connect`,
  );
}

export function disconnectConnection(
  connectionId: string,
  client: ApiClient = apiClient,
): Promise<SessionState> {
  return client.post<SessionState>(
    `/api/connections/${encodeURIComponent(connectionId)}/disconnect`,
  );
}

/** Drives the connected indicator. Cheap on the server — driver node state, no CQL. */
export function getConnectionHealth(
  connectionId: string,
  client: ApiClient = apiClient,
): Promise<ConnectionHealth> {
  return client.get<ConnectionHealth>(
    `/api/connections/${encodeURIComponent(connectionId)}/health`,
  );
}

export function getClusterCapabilities(
  connectionId: string,
  refresh = false,
  client: ApiClient = apiClient,
): Promise<ClusterCapabilities> {
  return client.get<ClusterCapabilities>(
    `/api/connections/${encodeURIComponent(connectionId)}/capabilities`,
    { query: { refresh } },
  );
}

/**
 * Test settings without saving.
 *
 * A failed probe is still a `200` with `success: false` — inspect the flag, do not rely on the
 * promise rejecting.
 */
export function testConnection(
  request: { connectionId: string } | { connection: ConnectionRequest },
  client: ApiClient = apiClient,
): Promise<ConnectionTestResult> {
  return client.post<ConnectionTestResult>('/api/connections/test', request);
}

/* ------------------------------------------------------------------------------ uploads */

/** Multipart upload of `secure-connect-<db>.zip`; stored encrypted, never echoed back. */
export function uploadSecureConnectBundle(
  connectionId: string,
  file: File,
  client: ApiClient = apiClient,
): Promise<ConnectionResponse> {
  const body = new FormData();
  body.append('file', file);
  return client.upload<ConnectionResponse>(
    `/api/connections/${encodeURIComponent(connectionId)}/secure-connect-bundle`,
    body,
  );
}

export function deleteSecureConnectBundle(
  connectionId: string,
  client: ApiClient = apiClient,
): Promise<void> {
  return client.delete<void>(
    `/api/connections/${encodeURIComponent(connectionId)}/secure-connect-bundle`,
  );
}

export interface KeystoreUpload {
  file: File;
  /** Store password. Write-only: encrypted at rest, never returned. */
  password?: string;
  storeType?: 'JKS' | 'PKCS12';
}

function keystoreForm({ file, password, storeType }: KeystoreUpload): FormData {
  const body = new FormData();
  body.append('file', file);
  if (password !== undefined) body.append('password', password);
  if (storeType) body.append('storeType', storeType);
  return body;
}

export function uploadTruststore(
  connectionId: string,
  upload: KeystoreUpload,
  client: ApiClient = apiClient,
): Promise<ConnectionResponse> {
  return client.upload<ConnectionResponse>(
    `/api/connections/${encodeURIComponent(connectionId)}/ssl/truststore`,
    keystoreForm(upload),
  );
}

export function uploadKeystore(
  connectionId: string,
  upload: KeystoreUpload,
  client: ApiClient = apiClient,
): Promise<ConnectionResponse> {
  return client.upload<ConnectionResponse>(
    `/api/connections/${encodeURIComponent(connectionId)}/ssl/keystore`,
    keystoreForm(upload),
  );
}
