/**
 * Connection form model and validation (plan §3 / §3.1). Pure — no React, no network.
 */
import type {
  AstraBundleDatacenter,
  ConnectionMode,
  ConnectionRequest,
  ContactPoint,
  ScbAcquisitionMode,
  ScbType,
} from '../api/types';

export interface CassandraFormState {
  contactPoints: string;
  localDatacenter: string;
  username: string;
  password: string;
  protocolVersion: string;
}

export interface AstraFormState {
  /** Full-privilege credential. Masked in the UI, never logged, never put in a URL. */
  astraToken: string;
  acquisitionMode: ScbAcquisitionMode;
  databaseId: string;
  /** Optional, and ORTHOGONAL to `scbType` — not a third value of it (plan §3.1 deviation 1). */
  region: string;
  scbType: ScbType;
  customDomain: string;
  /** UPLOAD mode. */
  bundleFileName: string;
  /** PATH mode — resolved on the SERVER, under CASSYX_SCB_PATH_ROOT. */
  bundlePath: string;
  keyspace: string;
}

export interface AdvancedFormState {
  applicationConf: string;
}

export interface ConnectionFormState {
  name: string;
  mode: ConnectionMode;
  cassandra: CassandraFormState;
  astra: AstraFormState;
  advanced: AdvancedFormState;
}

export function emptyConnectionForm(): ConnectionFormState {
  return {
    name: '',
    mode: 'CASSANDRA',
    cassandra: {
      contactPoints: '127.0.0.1:9042',
      localDatacenter: 'datacenter1',
      username: '',
      password: '',
      protocolVersion: '',
    },
    astra: {
      astraToken: '',
      // AUTO_DOWNLOAD is the default: token only, no UUID typing, no file hunting.
      acquisitionMode: 'AUTO_DOWNLOAD',
      databaseId: '',
      region: '',
      scbType: 'default',
      customDomain: '',
      bundleFileName: '',
      bundlePath: '',
      keyspace: '',
    },
    advanced: { applicationConf: '' },
  };
}

/** `host:port, host2` → structured contact points. Port defaults to 9042. */
export function parseContactPoints(raw: string): ContactPoint[] {
  return raw
    .split(/[,\s]+/)
    .map((entry) => entry.trim())
    .filter(Boolean)
    .map((entry) => {
      const lastColon = entry.lastIndexOf(':');
      if (lastColon === -1) return { host: entry, port: 9042 };
      const host = entry.slice(0, lastColon);
      const port = Number.parseInt(entry.slice(lastColon + 1), 10);
      return { host, port: Number.isNaN(port) ? 9042 : port };
    });
}

/** Astra tokens are `AstraCS:<clientId>:<secret>`. Shape check only — never logged. */
export function looksLikeAstraToken(token: string): boolean {
  return /^AstraCS:[A-Za-z0-9]+:[A-Za-z0-9]+$/.test(token.trim());
}

/**
 * Mask a secret for display. Used for the reveal toggle's "hidden" state fallback and for any
 * place a token would otherwise be echoed. NEVER returns more than the last 4 characters.
 */
export function maskSecret(secret: string | null | undefined): string {
  if (!secret) return '';
  const trimmed = secret.trim();
  if (trimmed.length <= 4) return '••••';
  return `${'•'.repeat(Math.min(trimmed.length - 4, 24))}${trimmed.slice(-4)}`;
}

/**
 * Strip anything token-shaped out of a string before it reaches an error message, a log line or
 * telemetry. The prior art rendered the Astra token in plaintext; this is the guard against
 * reintroducing that through an error path.
 */
export function redactSecrets(text: string): string {
  return text
    .replace(/AstraCS:[A-Za-z0-9._:-]+/g, 'AstraCS:[REDACTED]')
    .replace(/Bearer\s+[A-Za-z0-9._-]{8,}/gi, 'Bearer [REDACTED]');
}

export type ValidationErrors = Record<string, string>;

/** Validate the Astra portion of the form. Returns field → message. */
export function validateAstra(form: AstraFormState): ValidationErrors {
  const errors: ValidationErrors = {};

  if (!form.astraToken.trim()) {
    errors.astraToken = 'An Astra token is required.';
  } else if (!looksLikeAstraToken(form.astraToken)) {
    errors.astraToken = 'Expected the form AstraCS:<clientId>:<secret>.';
  }

  switch (form.acquisitionMode) {
    case 'AUTO_DOWNLOAD':
      if (!form.databaseId) errors.databaseId = 'Select a database.';
      if (form.scbType === 'custom' && !form.customDomain) {
        errors.customDomain = 'Select a custom domain, or switch the bundle type to “default”.';
      }
      break;
    case 'UPLOAD':
      if (!form.bundleFileName) errors.bundleFile = 'Choose a secure connect bundle (.zip).';
      break;
    case 'PATH':
      if (!form.bundlePath.trim()) {
        errors.bundlePath = 'Enter the server-side path to the bundle.';
      } else if (!form.bundlePath.trim().endsWith('.zip')) {
        errors.bundlePath = 'The secure connect bundle is a .zip file.';
      }
      break;
    default:
      break;
  }

  // Defensive: the ScbType union has exactly two members. Anything else is a bug upstream
  // (the DataStax reference implementation documents a third `region` type it never implements).
  if (form.scbType !== 'default' && form.scbType !== 'custom') {
    errors.scbType = 'Bundle type must be “default” or “custom”. Region is a separate field.';
  }

  return errors;
}

export function validateConnection(form: ConnectionFormState): ValidationErrors {
  const errors: ValidationErrors = {};
  if (!form.name.trim()) errors.name = 'Give this connection a name.';

  if (form.mode === 'CASSANDRA') {
    if (parseContactPoints(form.cassandra.contactPoints).length === 0) {
      errors.contactPoints = 'At least one contact point is required.';
    }
    if (!form.cassandra.localDatacenter.trim()) {
      errors.localDatacenter = 'The local datacenter name is required by the driver.';
    }
  } else if (form.mode === 'ASTRA') {
    Object.assign(errors, validateAstra(form.astra));
  } else if (!form.advanced.applicationConf.trim()) {
    errors.applicationConf = 'Paste an application.conf (HOCON) document.';
  }

  return errors;
}

/** Regions offered by the region dropdown, derived from the real bundle response. */
export function regionsFromBundles(bundles: AstraBundleDatacenter[]): string[] {
  return bundles.map((bundle) => bundle.region);
}

/**
 * Custom domains available for the currently selected region.
 * With no region selected we fall back to the first entry — the same selection rule the backend
 * uses (plan §3.1: "if no region is requested, take the first element").
 */
export function domainsForRegion(bundles: AstraBundleDatacenter[], region: string): string[] {
  if (bundles.length === 0) return [];
  const match = region
    ? bundles.find((bundle) => bundle.region.toLowerCase() === region.toLowerCase())
    : bundles[0];
  return match?.customDomainBundles?.map((entry) => entry.domain) ?? [];
}

/** Astra databases that can actually be connected to right now. */
export function isConnectableDatabase(status: string): boolean {
  return status === 'ACTIVE' || status === 'MAINTENANCE';
}

/** The driver's default request timeout, mirrored from `ConnectionRequest.requestTimeoutMillis`. */
export const DEFAULT_REQUEST_TIMEOUT_MS = 10_000;

const PROTOCOL_VERSIONS = ['V3', 'V4', 'V5', 'DSE_V1', 'DSE_V2'] as const;
type ProtocolVersion = (typeof PROTOCOL_VERSIONS)[number];

function parseProtocolVersion(raw: string): ProtocolVersion | undefined {
  const value = raw.trim().toUpperCase();
  return (PROTOCOL_VERSIONS as readonly string[]).includes(value)
    ? (value as ProtocolVersion)
    : undefined;
}

/** Blank string → `undefined`, so an untouched optional field is omitted rather than sent empty. */
function optional(value: string | undefined): string | undefined {
  const trimmed = value?.trim();
  return trimmed ? trimmed : undefined;
}

/**
 * Form state → the contract's `ConnectionRequest` (plan §3).
 *
 * Pure: no network, no React. Every secret is passed through untouched and every non-secret is
 * normalised, so the shape the server sees is the shape the tests assert.
 *
 * An empty secret field is sent as `undefined`, never `''`. The contract reads `undefined` as
 * "preserve what is stored" and `''` as "clear it" — and since the browser never receives the
 * stored secret, an untouched edit form shows an empty box for a password that very much exists.
 * Sending `''` there would silently wipe it. Clearing is a deliberate action the UI must offer
 * separately.
 */
export function toConnectionRequest(form: ConnectionFormState): ConnectionRequest {
  const secret = (value: string): string | undefined => (value ? value : undefined);

  const base = {
    name: form.name.trim(),
    mode: form.mode,
    requestTimeoutMillis: DEFAULT_REQUEST_TIMEOUT_MS,
  };

  if (form.mode === 'ASTRA') {
    return {
      ...base,
      defaultKeyspace: optional(form.astra.keyspace),
      astra: {
        astraToken: secret(form.astra.astraToken),
        scbMode: form.astra.acquisitionMode,
        databaseId: optional(form.astra.databaseId),
        region: optional(form.astra.region),
        scbType: form.astra.scbType,
        // The contract rejects a domain on a `default` bundle, so only send it where it belongs.
        domain: form.astra.scbType === 'custom' ? optional(form.astra.customDomain) : undefined,
        scbPath:
          form.astra.acquisitionMode === 'PATH' ? optional(form.astra.bundlePath) : undefined,
      },
    };
  }

  if (form.mode === 'ADVANCED') {
    return { ...base, advancedConfig: form.advanced.applicationConf };
  }

  return {
    ...base,
    contactPoints: parseContactPoints(form.cassandra.contactPoints),
    localDatacenter: optional(form.cassandra.localDatacenter),
    username: optional(form.cassandra.username),
    password: secret(form.cassandra.password),
    protocolVersion: parseProtocolVersion(form.cassandra.protocolVersion),
  };
}
