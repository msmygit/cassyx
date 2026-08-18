/**
 * Domain types for the app shell.
 *
 * These are thin aliases over the GENERATED contract types in `schema.d.ts`
 * (`npm run gen:api` ← `openapi/cassyx-api.yaml`). Aliasing rather than re-declaring means the
 * shell breaks loudly at `tsc` time if the contract changes shape, instead of drifting silently.
 *
 * Anything that is genuinely UI-only (view state, derived status) is declared here and marked
 * as such. Phase 1 workstreams should import feature payload types straight from
 * `components['schemas'][...]`.
 */
import type { components } from './schema';

export type Schemas = components['schemas'];

/* ------------------------------------------------------------------ licensing (plan §9) */

/**
 * `edition: "unlicensed-bypass"` / `bypass: true` is returned when `CASSYX_LICENSE_ENFORCE=false`.
 * The UI MUST keep a persistent banner visible in that state (plan §9.2) so a bypassed instance
 * is never mistaken for a paid one.
 */
export type LicenseStatus = Schemas['LicenseStatus'];
export type LicenseEdition = LicenseStatus['edition'];
export type ActivateLicenseRequest = Schemas['LicenseActivationRequest'];
export type CheckoutSessionResponse = Schemas['CheckoutSessionResponse'];
export type ServiceHealth = Schemas['ServiceHealth'];

/* ---------------------------------------------------------------- connections (plan §3) */

export type ConnectionMode = Schemas['ConnectionMode'];
export type ContactPoint = Schemas['ContactPoint'];
export type ConnectionRequest = Schemas['ConnectionRequest'];

/** Response shape — secrets are presence flags only (`hasPassword`, `astra.hasToken`, …). */
export type ConnectionSummary = Schemas['ConnectionResponse'];

export type SessionState = Schemas['SessionState'];

/** UI-only: the connection indicator in the top bar has states the API does not model. */
export type SessionStatus = 'DISCONNECTED' | 'CONNECTING' | 'CONNECTED' | 'ERROR';

/* --------------------------------------------------- Astra SCB acquisition (plan §3.1) */

/** `AUTO_DOWNLOAD` (default) | `UPLOAD` | `PATH`. */
export type ScbMode = Schemas['ScbMode'];
/** Alias kept for readability at call sites. */
export type ScbAcquisitionMode = ScbMode;

/**
 * Bundle flavour. Deliberately TWO values — `region` is a separate, orthogonal field. The
 * DataStax reference implementation documents a third `region` type its switch never implements
 * (plan §3.1 deviation 1). Do not add it back.
 */
export type ScbType = Schemas['ScbType'];

export type AstraSettings = Schemas['AstraSettings'];

/** From `GET /api/astra/databases` — powers the database picker, so no UUID is ever typed. */
export type AstraDatabase = Schemas['AstraDatabase'];
export type AstraDatabaseStatus = AstraDatabase['status'];

/**
 * From `GET /api/astra/databases/{databaseId}/bundles` — one entry per datacenter, mirroring the
 * DevOps `secureBundleURL` response.
 */
export type AstraBundleDatacenter = Schemas['AstraBundleDatacenter'];
export type AstraCustomDomainBundle = Schemas['AstraCustomDomainBundle'];

/* ------------------------------------------------------------------- schema (plan §4/§7) */

export type SchemaNodeKind = Schemas['SchemaNodeKind'];
export type ColumnKind = Schemas['ColumnKind'];
export type ClusterCapabilities = Schemas['ClusterCapabilities'];

/* ---------------------------------------------------------------------- jobs (plan §5.5) */

export type Job = Schemas['Job'];
export type JobType = Schemas['JobType'];
export type JobStatus = Schemas['JobStatus'];
export type JobProgress = Schemas['JobProgress'];
