/**
 * Transport seam for the Astra DevOps-backed endpoints (plan §3.1).
 *
 * Kept separate from the form component so tests can swap the implementation without touching the
 * UI — and so the component module exports components only.
 */
import { downloadAstraBundle, listAstraBundles, listAstraDatabases } from '../api/endpoints';
import type { AstraBundleDatacenter, AstraDatabase, ScbType } from '../api/types';

export interface RedownloadOptions {
  /** The connection the refreshed bundle is stored against, encrypted. */
  connectionId: string;
  region?: string;
  scbType?: ScbType;
  domain?: string;
}

export interface AstraApi {
  listDatabases: (astraToken: string) => Promise<AstraDatabase[]>;
  listBundles: (databaseId: string, astraToken: string) => Promise<AstraBundleDatacenter[]>;
  /** Force a fresh download, bypassing the server-side bundle cache. */
  redownload: (
    databaseId: string,
    astraToken: string,
    options: RedownloadOptions,
  ) => Promise<unknown>;
}

/**
 * SECURITY: the token is sent in the `X-Astra-Token` header (or a request body), never in a URL
 * or query string — those end up in access logs, browser history and `Referer` headers.
 */
export const defaultAstraApi: AstraApi = {
  listDatabases: (astraToken) => listAstraDatabases(astraToken),
  listBundles: (databaseId, astraToken) => listAstraBundles(databaseId, astraToken),
  redownload: (databaseId, astraToken, options) =>
    downloadAstraBundle(databaseId, {
      connectionId: options.connectionId,
      astraToken,
      region: options.region || undefined,
      scbType: options.scbType,
      domain: options.scbType === 'custom' ? options.domain || undefined : undefined,
      // Astra rotates bundles, and a stale one fails with an opaque TLS error rather than an
      // obvious one (plan §3.1 deviation 5) — which is exactly why this action exists.
      force: true,
    }),
};
