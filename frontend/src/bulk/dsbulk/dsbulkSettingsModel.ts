/**
 * DSBulk settings model (plan §5.3). Pure — no React, no network.
 *
 * The UI edits a FLAT `Record<path, string>` because that is what DSBulk itself speaks (every
 * option is a dotted path with a string value on the command line and in HOCON). The contract
 * transports a NESTED `DsbulkSettings` document, so this module owns the two conversions plus the
 * merge with server-derived defaults and the validation rules DSBulk applies to the odd value
 * shapes (`8C` multipliers, `1%` error thresholds).
 */
import type { Schemas } from '../../api/types';
import {
  findSetting,
  type DsbulkSettingDef,
  type DsbulkSettingGroup,
} from './dsbulkSettingsCatalog';

export type DsbulkSettings = Schemas['DsbulkSettings'];
export type DerivedSetting = Schemas['DerivedSetting'];

/** Every value is a string: this is the DSBulk wire form, and what a text input holds. */
export type FlatSettings = Record<string, string>;

/* ------------------------------------------------------------------------------- flatten */

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/** `k=v, k2=v2` — how a DSBulk map option is written on the command line. */
export function serialiseMap(map: Record<string, unknown>): string {
  return Object.entries(map)
    .map(([key, value]) => `${key}=${String(value)}`)
    .join(', ');
}

export function parseMap(
  raw: string,
  valueKind: 'string' | 'boolean' = 'string',
): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const part of raw.split(',')) {
    const trimmed = part.trim();
    if (!trimmed) continue;
    const eq = trimmed.indexOf('=');
    if (eq < 0) continue;
    const key = trimmed.slice(0, eq).trim();
    const value = trimmed.slice(eq + 1).trim();
    if (!key) continue;
    out[key] = valueKind === 'boolean' ? value === 'true' : value;
  }
  return out;
}

function isMapPath(path: string): boolean {
  if (path === 'extra' || path.endsWith('.extra')) return true;
  return findSetting(path)?.kind === 'map';
}

function flattenInto(value: unknown, prefix: string, out: FlatSettings): void {
  if (value === undefined || value === null) return;
  if (Array.isArray(value)) {
    out[prefix] = value.map((item) => String(item)).join(', ');
    return;
  }
  if (isPlainObject(value)) {
    if (isMapPath(prefix)) {
      out[prefix] = serialiseMap(value);
      return;
    }
    for (const [key, child] of Object.entries(value)) {
      flattenInto(child, prefix ? `${prefix}.${key}` : key, out);
    }
    return;
  }
  out[prefix] = String(value);
}

/**
 * Flatten a nested `DsbulkSettings` document to `{ 'batch.maxBatchStatements': '32' }`.
 *
 * `extra` maps and the modelled map options are serialised as `k=v` lists rather than recursed
 * into, because their keys are opaque and may themselves contain dots.
 */
export function flattenSettings(settings: DsbulkSettings | undefined | null): FlatSettings {
  const out: FlatSettings = {};
  if (!settings) return out;
  flattenInto(settings, '', out);
  return out;
}

/* ----------------------------------------------------------------------------- unflatten */

function coerceValue(def: DsbulkSettingDef | undefined, raw: string): unknown {
  if (!def) return raw;
  switch (def.kind) {
    case 'number': {
      const parsed = Number(raw);
      return Number.isFinite(parsed) ? parsed : raw;
    }
    case 'boolean':
      return raw === 'true';
    case 'stringList':
      // Empty elements are dropped: the comma-separated text form cannot distinguish "" from a
      // trailing separator. Use the `extra` escape hatch for a list that must contain an empty
      // string (`codec.nullStrings` is the only realistic case).
      return raw
        .split(',')
        .map((item) => item.trim())
        .filter((item) => item.length > 0);
    case 'map':
      return parseMap(raw, def.mapValue ?? 'string');
    default:
      return raw;
  }
}

function setDeep(root: Record<string, unknown>, path: string, value: unknown): void {
  const segments = path.split('.');
  let cursor = root;
  for (let index = 0; index < segments.length - 1; index += 1) {
    const segment = segments[index] as string;
    const existing = cursor[segment];
    if (isPlainObject(existing)) {
      cursor = existing;
    } else {
      const created: Record<string, unknown> = {};
      cursor[segment] = created;
      cursor = created;
    }
  }
  const last = segments[segments.length - 1] as string;
  cursor[last] = value;
}

/**
 * Rebuild the nested contract document. Empty strings are treated as "not set" and dropped, so a
 * cleared field falls back to the derived/upstream default rather than sending `""`.
 *
 * Keys reported under DSBulk's own path (`driver.basic.request.consistency`) are normalised back
 * onto the contract's field name.
 */
export function unflattenSettings(flat: FlatSettings): DsbulkSettings {
  const root: Record<string, unknown> = {};
  for (const [path, raw] of Object.entries(flat)) {
    if (raw === '') continue;
    const def = findSetting(path);
    const target = def?.path ?? path;
    if (path === 'extra' || path.endsWith('.extra')) {
      setDeep(root, target, parseMap(raw, 'string'));
      continue;
    }
    setDeep(root, target, coerceValue(def, raw));
  }
  return root as DsbulkSettings;
}

/** Round-trip helper used by tests and by the "reset" action. */
export function normalisePath(path: string): string {
  return findSetting(path)?.path ?? path;
}

/* --------------------------------------------------------------------------------- merge */

export interface ResolvedSetting {
  path: string;
  /** The value to render in the field. */
  value: string;
  /** `true` while the value is still the server-derived one. Any user edit clears it. */
  auto: boolean;
  /** Why the server chose the auto value — the "auto" chip's tooltip. */
  rationale?: string;
  /** The derived value, retained so an edited field can be reset back to auto. */
  autoValue?: string;
  upstreamDefault?: string;
  group?: DsbulkSettingGroup;
  docsUrl?: string;
}

export type ResolvedSettings = Record<string, ResolvedSetting>;

/**
 * Merge the server's `DerivedSetting[]` with the user's overrides.
 *
 * The `auto` flag is preserved exactly as reported until the user supplies an override for that
 * path, at which point it flips to `false` — the derived value and its rationale are kept so the
 * field can be reset.
 */
export function resolveSettings(
  derived: readonly DerivedSetting[] = [],
  overrides: FlatSettings = {},
): ResolvedSettings {
  const resolved: ResolvedSettings = {};

  for (const setting of derived) {
    const path = normalisePath(setting.path);
    const def = findSetting(path);
    resolved[path] = {
      path,
      value: setting.value,
      auto: setting.auto,
      autoValue: setting.auto ? setting.value : undefined,
      rationale: setting.rationale,
      upstreamDefault: setting.upstreamDefault ?? def?.upstreamDefault,
      group: (setting.group as DsbulkSettingGroup | undefined) ?? def?.group,
      docsUrl: setting.docsUrl ?? def?.docsUrl,
    };
  }

  for (const [rawPath, value] of Object.entries(overrides)) {
    const path = normalisePath(rawPath);
    const previous = resolved[path];
    const def = findSetting(path);
    resolved[path] = {
      ...(previous ?? {}),
      path,
      value,
      // A user edit is never "auto", however the server originally reported it.
      auto: false,
      autoValue: previous?.autoValue,
      rationale: previous?.rationale,
      upstreamDefault: previous?.upstreamDefault ?? def?.upstreamDefault,
      group: previous?.group ?? def?.group,
      docsUrl: previous?.docsUrl ?? def?.docsUrl,
    };
  }

  return resolved;
}

/** The value a field should display: the user override, else the derived value, else empty. */
export function displayValue(resolved: ResolvedSettings, path: string): string {
  return resolved[normalisePath(path)]?.value ?? '';
}

export function isAuto(resolved: ResolvedSettings, path: string): boolean {
  return resolved[normalisePath(path)]?.auto === true;
}

/**
 * Apply a user edit.
 *
 * An empty value is kept as an explicit override rather than deleted: otherwise clearing a field
 * that currently shows a derived value would instantly repopulate it, and the user could never
 * type over it. Reverting to the derived value is the explicit `clearOverride` action, and
 * `unflattenSettings` drops empty strings before the request goes out.
 */
export function applyOverride(overrides: FlatSettings, path: string, value: string): FlatSettings {
  return { ...overrides, [normalisePath(path)]: value };
}

export function clearOverride(overrides: FlatSettings, path: string): FlatSettings {
  const next = { ...overrides };
  delete next[normalisePath(path)];
  return next;
}

/** Drop write-only credentials — used before persisting a job template or logging anything. */
export function stripSecrets(flat: FlatSettings): FlatSettings {
  const out: FlatSettings = {};
  for (const [path, value] of Object.entries(flat)) {
    if (findSetting(path)?.secret === true) continue;
    out[path] = value;
  }
  return out;
}

/* ---------------------------------------------------------------------------- validation */

const INTEGER = /^-?\d+$/;
const NUMBER = /^-?\d+(\.\d+)?$/;
/** `8C`, `0.5C`, `16` or the literal `AUTO`. */
const CONCURRENCY = /^(AUTO|-?\d+(\.\d+)?C?)$/;
/** An absolute count or a percentage such as `1%` / `0.5%`. */
const ERROR_THRESHOLD = /^(-?\d+|\d+(\.\d+)?%)$/;

export function isConcurrencyExpression(value: string): boolean {
  return CONCURRENCY.test(value.trim());
}

export function isErrorThreshold(value: string): boolean {
  return ERROR_THRESHOLD.test(value.trim());
}

export function isIntegerString(value: string): boolean {
  return INTEGER.test(value.trim());
}

export function isNumberString(value: string): boolean {
  return NUMBER.test(value.trim());
}

/** `undefined` means valid. Empty input is always valid: it just means "use the default". */
export function validateSettingValue(
  def: DsbulkSettingDef | undefined,
  raw: string,
): string | undefined {
  const value = raw.trim();
  if (value === '') return undefined;

  if (def?.format === 'concurrency') {
    return isConcurrencyExpression(value)
      ? undefined
      : 'Expected a number or an NC multiplier such as 8C or 0.5C.';
  }
  if (def?.format === 'errorThreshold') {
    return isErrorThreshold(value)
      ? undefined
      : 'Expected a count such as 100 or a percentage such as 1%.';
  }
  if (!def) return undefined;

  switch (def.kind) {
    case 'number':
      return isNumberString(value) ? undefined : 'Expected a number.';
    case 'boolean':
      return value === 'true' || value === 'false' ? undefined : 'Expected true or false.';
    case 'enum':
      return !def.enumValues || def.enumValues.includes(value)
        ? undefined
        : `Expected one of: ${def.enumValues.join(', ')}.`;
    case 'stringList': {
      if (!def.enumValues) return undefined;
      const items = value
        .split(',')
        .map((item) => item.trim())
        .filter((item) => item.length > 0);
      const bad = items.filter((item) => !def.enumValues?.includes(item));
      return bad.length === 0 ? undefined : `Unknown value(s): ${bad.join(', ')}.`;
    }
    case 'map':
      return value.split(',').every((part) => !part.trim() || part.includes('='))
        ? undefined
        : 'Expected comma-separated key=value pairs.';
    default:
      return undefined;
  }
}

/** Validate a whole flat override set. Returns `{ path: message }` for the invalid entries. */
export function validateFlatSettings(flat: FlatSettings): Record<string, string> {
  const errors: Record<string, string> = {};
  for (const [path, value] of Object.entries(flat)) {
    const message = validateSettingValue(findSetting(path), value);
    if (message) errors[normalisePath(path)] = message;
  }
  return errors;
}

/* ------------------------------------------------------------------------------- mapping */

export interface MappingRow {
  /** Source field name, or a zero-based index for headerless CSV. */
  field: string;
  /** Target CQL column. */
  column: string;
}

/** Build DSBulk's `a=b, c=d` mapping string. Incomplete rows are skipped. */
export function buildMappingString(rows: readonly MappingRow[]): string {
  return rows
    .filter((row) => row.field.trim() !== '' && row.column.trim() !== '')
    .map((row) => `${row.field.trim()}=${row.column.trim()}`)
    .join(', ');
}

export function parseMappingString(mapping: string): MappingRow[] {
  const rows: MappingRow[] = [];
  for (const part of mapping.split(',')) {
    const trimmed = part.trim();
    if (!trimmed) continue;
    const eq = trimmed.indexOf('=');
    if (eq < 0) continue;
    const field = trimmed.slice(0, eq).trim();
    const column = trimmed.slice(eq + 1).trim();
    if (!field || !column) continue;
    rows.push({ field, column });
  }
  return rows;
}

/* -------------------------------------------------------------------------- request build */

export type LoadSource = Schemas['LoadSource'];
export type ExportFormat = Schemas['ExportFormat'];
export type LoadJobRequest = Schemas['LoadJobRequest'];
export type CountJobRequest = Schemas['CountJobRequest'];

export interface LoadJobDraft {
  name: string;
  keyspace: string;
  table: string;
  source: LoadSource;
  mapping: string;
  dryRun: boolean;
  overrides: FlatSettings;
  templateId?: string;
}

/** `undefined` when the draft is not submittable, plus the reasons why. */
export function validateLoadDraft(draft: LoadJobDraft): Record<string, string> {
  const errors: Record<string, string> = {};
  if (!draft.keyspace.trim()) errors.keyspace = 'Keyspace is required.';
  if (!draft.table.trim()) errors.table = 'Table is required.';
  const { uploadId, path, s3Uri } = draft.source;
  if (!uploadId && !path?.trim() && !s3Uri?.trim()) {
    errors.source = 'Choose an uploaded file, a server path or an S3 URI.';
  }
  return { ...errors, ...validateFlatSettings(draft.overrides) };
}

export function buildLoadJobRequest(draft: LoadJobDraft): LoadJobRequest {
  const request: LoadJobRequest = {
    keyspace: draft.keyspace.trim(),
    table: draft.table.trim(),
    source: draft.source,
    dryRun: draft.dryRun,
    dsbulkSettings: unflattenSettings(draft.overrides),
  };
  if (draft.name.trim()) request.name = draft.name.trim();
  if (draft.mapping.trim()) request.mapping = draft.mapping.trim();
  if (draft.templateId) request.templateId = draft.templateId;
  return request;
}
