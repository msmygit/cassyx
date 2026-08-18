/**
 * Transport seam for the Astra DevOps-backed endpoints (plan §3.1).
 *
 * Kept separate from the form component so tests and Phase 1 can swap the implementation without
 * touching the UI — and so the component module exports components only.
 */
import { downloadAstraBundle, listAstraBundles, listAstraDatabases } from '../api/endpoints';
import type { AstraBundleDatacenter, AstraDatabase } from '../api/types';

export interface AstraApi {
  listDatabases: (astraToken: string) => Promise<AstraDatabase[]>;
  listBundles: (databaseId: string, astraToken: string) => Promise<AstraBundleDatacenter[]>;
  /** Force a fresh download, bypassing the server-side bundle cache. */
  redownload: (databaseId: string, astraToken: string) => Promise<unknown>;
}

/**
 * SECURITY: the token is sent in the `X-Astra-Token` header (or a request body), never in a URL
 * or query string — those end up in access logs, browser history and `Referer` headers.
 */
export const defaultAstraApi: AstraApi = {
  listDatabases: (astraToken) => listAstraDatabases(astraToken),
  listBundles: (databaseId, astraToken) => listAstraBundles(databaseId, astraToken),
  redownload: (databaseId, astraToken) =>
    downloadAstraBundle(databaseId, { connectionId: '', astraToken, force: true }),
};
