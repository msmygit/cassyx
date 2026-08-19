/**
 * Thin, typed call sites for the endpoints the app *shell* needs.
 *
 * Feature endpoints (schema, query, bulk, vector) are owned by the Phase 1 workstreams and belong
 * in their own modules alongside their features — this file stays small on purpose.
 */
import { apiClient, type ApiClient } from './client';
import type {
  ActivateLicenseRequest,
  AstraBundleDatacenter,
  AstraDatabase,
  CheckoutSessionResponse,
  ConnectionSummary,
  LicenseStatus,
  ScbType,
  SessionState,
  TrialRequest,
} from './types';

/* --------------------------------------------------------------------------- license §9 */

export function fetchLicenseStatus(client: ApiClient = apiClient): Promise<LicenseStatus> {
  return client.get<LicenseStatus>('/api/license');
}

export function activateLicense(
  request: ActivateLicenseRequest,
  client: ApiClient = apiClient,
): Promise<LicenseStatus> {
  return client.post<LicenseStatus>('/api/license/activate', request);
}

/**
 * `email` pre-fills Stripe Checkout. Pass it whenever the caller already knows one (e.g. an
 * EXPIRED or UPGRADE_REQUIRED licence retains the original buyer's address) — see
 * docs/integration-todo.md for the case where none is known yet.
 */
export function createCheckoutSession(
  email?: string,
  client: ApiClient = apiClient,
): Promise<CheckoutSessionResponse> {
  return client.post<CheckoutSessionResponse>('/api/billing/checkout-session', {
    email: email ?? '',
    quantity: 1,
  });
}

/** Requests a 14-day trial key (plan §9.4). Needs egress; 503 means "use /api/license/activate". */
export function requestTrial(
  request: TrialRequest,
  client: ApiClient = apiClient,
): Promise<LicenseStatus> {
  return client.post<LicenseStatus>('/api/license/trial', request);
}

/* ----------------------------------------------------------------------- connections §3 */

export function listConnections(client: ApiClient = apiClient): Promise<ConnectionSummary[]> {
  return client.get<ConnectionSummary[]>('/api/connections');
}

export function listSessions(client: ApiClient = apiClient): Promise<SessionState[]> {
  return client.get<SessionState[]>('/api/sessions');
}

/* --------------------------------------------------------- Astra SCB auto-download §3.1 */

/**
 * The Astra token travels in the `X-Astra-Token` header, per the contract.
 *
 * SECURITY: never a query parameter. Query strings land in access logs, proxy logs, browser
 * history and `Referer` headers.
 */
function astraHeaders(astraToken: string): Record<string, string> {
  return { 'X-Astra-Token': astraToken };
}

/** Enumerate the databases visible to a token, for the database picker (no UUID typing). */
export function listAstraDatabases(
  astraToken: string,
  client: ApiClient = apiClient,
): Promise<AstraDatabase[]> {
  return client.get<AstraDatabase[]>('/api/astra/databases', {
    headers: astraHeaders(astraToken),
  });
}

/** Bundle options (one entry per datacenter: region + custom domains) for one database. */
export function listAstraBundles(
  databaseId: string,
  astraToken: string,
  client: ApiClient = apiClient,
): Promise<AstraBundleDatacenter[]> {
  return client.get<AstraBundleDatacenter[]>(
    `/api/astra/databases/${encodeURIComponent(databaseId)}/bundles`,
    { headers: astraHeaders(astraToken) },
  );
}

export interface DownloadAstraBundleRequest {
  connectionId: string;
  astraToken: string;
  region?: string;
  scbType?: ScbType;
  domain?: string;
  /** `true` bypasses the server-side cache — the "re-download bundle" action. */
  force?: boolean;
}

/**
 * Download (or re-download) the bundle and store it encrypted against the connection.
 *
 * Astra rotates bundles, and a stale one fails with an opaque TLS error rather than an obvious
 * one (plan §3.1 deviation 5) — hence the explicit `force` refresh.
 */
export function downloadAstraBundle(
  databaseId: string,
  request: DownloadAstraBundleRequest,
  client: ApiClient = apiClient,
): Promise<unknown> {
  return client.post(`/api/astra/databases/${encodeURIComponent(databaseId)}/bundle/download`, {
    force: false,
    ...request,
  });
}
