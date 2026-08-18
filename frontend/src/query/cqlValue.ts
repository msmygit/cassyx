/**
 * Type-aware rendering and parsing of the contract's `CqlValue` wire encoding.
 *
 * Two rules from plan §7 / the contract that this file exists to keep:
 *
 * 1. **`bigint` / `varint` / `counter` / `decimal` and token values arrive as STRINGS.** They exceed
 *    `Number.MAX_SAFE_INTEGER`, so `Number(v)` here would silently round them — a data-corruption
 *    bug with no error anywhere. Nothing in this file ever coerces one to `number`.
 * 2. **`null` and *unset* are different.** `null` writes a tombstone; unset writes nothing at all.
 *    An absent key also means unset. Both are rendered distinctly rather than as an empty cell.
 */
import { UNSET, type CellState, type ColumnMetadata } from './types';

/** Coarse families the grid picks a renderer/editor from. */
export type CqlKind =
  | 'text'
  | 'bignum'
  | 'number'
  | 'boolean'
  | 'uuid'
  | 'timeuuid'
  | 'timestamp'
  | 'date'
  | 'time'
  | 'duration'
  | 'inet'
  | 'blob'
  | 'counter'
  | 'list'
  | 'set'
  | 'map'
  | 'tuple'
  | 'udt'
  | 'vector'
  | 'unknown';

/** Types transported as strings because JSON numbers cannot hold them. */
const BIG_NUMERIC = new Set(['bigint', 'varint', 'decimal', 'counter']);
const SMALL_NUMERIC = new Set(['int', 'smallint', 'tinyint', 'float', 'double']);
const TEXTUAL = new Set(['text', 'varchar', 'ascii']);

export function cqlKind(
  column: Pick<ColumnMetadata, 'type' | 'vector' | 'udt'> | undefined,
): CqlKind {
  if (!column) return 'unknown';
  if (column.vector) return 'vector';
  if (column.udt) return 'udt';
  const type = (column.type ?? '').trim().toLowerCase();
  if (type.startsWith('list<')) return 'list';
  if (type.startsWith('set<')) return 'set';
  if (type.startsWith('map<')) return 'map';
  if (type.startsWith('tuple<')) return 'tuple';
  if (type.startsWith('vector<')) return 'vector';
  if (type.startsWith('frozen<')) return cqlKind({ ...column, type: type.slice(7, -1) });
  if (type === 'counter') return 'counter';
  if (BIG_NUMERIC.has(type)) return 'bignum';
  if (SMALL_NUMERIC.has(type)) return 'number';
  if (TEXTUAL.has(type)) return 'text';
  if (type === 'boolean') return 'boolean';
  if (type === 'timeuuid') return 'timeuuid';
  if (type === 'uuid') return 'uuid';
  if (type === 'timestamp') return 'timestamp';
  if (type === 'date') return 'date';
  if (type === 'time') return 'time';
  if (type === 'duration') return 'duration';
  if (type === 'inet') return 'inet';
  if (type === 'blob') return 'blob';
  return 'unknown';
}

export function cellState(row: Record<string, unknown> | undefined, column: string): CellState {
  if (!row || !(column in row)) return 'unset';
  const value = row[column];
  if (value === UNSET) return 'unset';
  if (value === null || value === undefined) return 'null';
  return 'value';
}

export function isUnset(value: unknown): boolean {
  return value === UNSET;
}

/* ------------------------------------------------------------------------------- blob */

const BASE64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';

/** Decodes base64 without `atob`, so it behaves identically in jsdom and the browser. */
export function base64ToBytes(base64: string): Uint8Array {
  const clean = base64.replace(/[^A-Za-z0-9+/]/g, '');
  const bytes: number[] = [];
  let buffer = 0;
  let bits = 0;
  for (const char of clean) {
    const index = BASE64.indexOf(char);
    if (index < 0) continue;
    buffer = (buffer << 6) | index;
    bits += 6;
    if (bits >= 8) {
      bits -= 8;
      bytes.push((buffer >> bits) & 0xff);
    }
  }
  return Uint8Array.from(bytes);
}

export function bytesToHex(bytes: Uint8Array): string {
  return Array.from(bytes)
    .map((byte) => byte.toString(16).padStart(2, '0'))
    .join('');
}

/** `0x…` hex rendering of a base64 blob — the form CQL itself uses. */
export function blobToHex(base64: string): string {
  return `0x${bytesToHex(base64ToBytes(base64))}`;
}

/* --------------------------------------------------------------------------- timeuuid */

/** Gregorian epoch (1582-10-15) to Unix epoch, in 100-nanosecond intervals. */
const GREGORIAN_OFFSET_100NS = 122192928000000000n;

/**
 * Decodes the embedded timestamp of a version-1 UUID.
 *
 * `timeuuid` values are ordered by this timestamp, so showing it turns an opaque hex string into
 * the "when" it actually encodes. Returns `null` for anything that is not a v1 UUID.
 */
export function timeuuidTimestamp(uuid: string): Date | null {
  const hex = uuid.replace(/-/g, '');
  if (hex.length !== 32) return null;
  if (hex[12] !== '1') return null;
  const timeLow = hex.slice(0, 8);
  const timeMid = hex.slice(8, 12);
  const timeHigh = hex.slice(13, 16);
  const intervals = BigInt(`0x${timeHigh}${timeMid}${timeLow}`);
  const unix100ns = intervals - GREGORIAN_OFFSET_100NS;
  const millis = unix100ns / 10000n;
  if (millis < -8640000000000000n || millis > 8640000000000000n) return null;
  return new Date(Number(millis));
}

/* --------------------------------------------------------------------------- rendering */

export interface RenderOptions {
  /** `hex` is the CQL-native form; `base64` is what the wire carries. */
  blobFormat?: 'hex' | 'base64';
  /** Collapse long collections/vectors to a summary. */
  compact?: boolean;
}

/** Text used in the grid, in exports and in the copy-to-clipboard path. */
export function renderValue(
  value: unknown,
  column?: Pick<ColumnMetadata, 'type' | 'vector' | 'udt'>,
  options: RenderOptions = {},
): string {
  if (value === UNSET) return '⊘ unset';
  if (value === null || value === undefined) return 'null';

  const kind = cqlKind(column);
  if (kind === 'blob' && typeof value === 'string') {
    return options.blobFormat === 'base64' ? value : blobToHex(value);
  }
  if (kind === 'vector' && Array.isArray(value)) {
    return options.compact ? `vector[${value.length}]` : `[${value.join(', ')}]`;
  }
  if (Array.isArray(value)) {
    if (options.compact && value.length > 8) {
      return `[${value
        .slice(0, 8)
        .map((v) => renderValue(v))
        .join(', ')}, … +${value.length - 8}]`;
    }
    return `[${value.map((v) => renderValue(v)).join(', ')}]`;
  }
  if (typeof value === 'object') {
    const entries = Object.entries(value as Record<string, unknown>);
    return `{${entries.map(([k, v]) => `${k}: ${renderValue(v)}`).join(', ')}}`;
  }
  return String(value);
}

/** Secondary line the grid shows under a cell, when the raw value hides something useful. */
export function renderAnnotation(
  value: unknown,
  column?: Pick<ColumnMetadata, 'type' | 'vector' | 'udt'>,
): string | null {
  if (value === null || value === undefined || value === UNSET) return null;
  const kind = cqlKind(column);
  if (kind === 'timeuuid' && typeof value === 'string') {
    const date = timeuuidTimestamp(value);
    return date ? date.toISOString() : null;
  }
  if (kind === 'blob' && typeof value === 'string') {
    return `${base64ToBytes(value).length} bytes`;
  }
  if (kind === 'vector' && Array.isArray(value)) {
    return `${value.length} dimensions`;
  }
  return null;
}

/* ---------------------------------------------------------------------------- editing */

/**
 * Turns editor text back into a wire value for the given column.
 *
 * Big numerics stay strings on purpose — round-tripping a `bigint` through `Number` is exactly the
 * silent corruption this module exists to prevent.
 */
export function parseEditorValue(
  text: string,
  column?: Pick<ColumnMetadata, 'type' | 'vector' | 'udt'>,
): unknown {
  const trimmed = text.trim();
  if (trimmed === UNSET || trimmed === '⊘ unset') return UNSET;
  if (trimmed === 'null') return null;

  const kind = cqlKind(column);
  switch (kind) {
    case 'bignum':
    case 'counter':
      return trimmed;
    case 'number': {
      const parsed = Number(trimmed);
      if (Number.isNaN(parsed))
        throw new Error(`"${text}" is not a valid ${column?.type ?? 'number'}`);
      return parsed;
    }
    case 'boolean':
      if (trimmed === 'true') return true;
      if (trimmed === 'false') return false;
      throw new Error(`"${text}" is not a boolean`);
    case 'list':
    case 'set':
    case 'map':
    case 'tuple':
    case 'udt':
    case 'vector':
      try {
        return JSON.parse(trimmed);
      } catch {
        throw new Error(`"${text}" is not valid JSON for ${column?.type ?? 'this column'}`);
      }
    default:
      return text;
  }
}

/** Column names of a result set, in projection order. */
export function columnNames(columns: ColumnMetadata[]): string[] {
  return columns.map((column) => column.name);
}

/** Comparator used by the client-side sort, correct for values that arrive as strings. */
export function compareValues(a: unknown, b: unknown, kind: CqlKind): number {
  if (a === b) return 0;
  if (a === null || a === undefined || a === UNSET) return 1;
  if (b === null || b === undefined || b === UNSET) return -1;
  if (kind === 'bignum' || kind === 'counter') {
    try {
      const left = BigInt(String(a).split('.')[0] ?? '0');
      const right = BigInt(String(b).split('.')[0] ?? '0');
      return left === right ? 0 : left < right ? -1 : 1;
    } catch {
      return String(a).localeCompare(String(b));
    }
  }
  if (kind === 'number') return Number(a) - Number(b);
  if (kind === 'boolean') return Number(a) - Number(b);
  return renderValue(a).localeCompare(renderValue(b), undefined, { numeric: true });
}
