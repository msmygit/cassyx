/**
 * Pure vector logic (plan §6). No React, no network — all of it unit-tested.
 *
 * Two rules drive this file:
 *
 * 1. **A vector is never rendered as 1536 comma-separated floats.** The grid gets a sparkline and
 *    a dimension badge; the full values live behind the inspector.
 * 2. **Unsupported features are hidden with an explanation, never shown broken** (plan §7.1).
 *    `capabilityState` returns the cluster's own reason string so the tooltip says *why*.
 */
import type {
  AnnPredicate,
  AnnQueryRequest,
  CapabilityName,
  ClusterCapabilities,
  SimilarityFunction,
  VectorColumn,
} from './types';

/** Points drawn in a grid-cell sparkline. Far fewer than a real vector has, deliberately. */
export const SPARKLINE_SAMPLES = 64;

/* ------------------------------------------------------------------ parsing input */

export class VectorParseError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'VectorParseError';
  }
}

/**
 * Parses a pasted or uploaded query vector.
 *
 * Accepts a JSON array, a bracketed list, or bare comma/whitespace-separated numbers — because
 * people paste whatever their notebook printed, and rejecting `[0.1, 0.2]` for its brackets is
 * the kind of friction that makes a tool feel hostile.
 *
 * @param expectedDimensions when given, a length mismatch is an error rather than a later 400
 */
export function parseVectorText(text: string, expectedDimensions?: number): number[] {
  const trimmed = (text ?? '').trim();
  if (!trimmed) {
    throw new VectorParseError('Paste a vector first.');
  }

  const body = trimmed.replace(/^\[/, '').replace(/\]$/, '');
  const tokens = body
    .split(/[\s,]+/)
    .map((token) => token.trim())
    .filter((token) => token.length > 0);

  if (tokens.length === 0) {
    throw new VectorParseError('That vector is empty.');
  }

  const values = tokens.map((token, index) => {
    const value = Number(token);
    if (!Number.isFinite(value)) {
      throw new VectorParseError(`"${token}" at position ${index + 1} is not a number.`);
    }
    return value;
  });

  if (expectedDimensions !== undefined && values.length !== expectedDimensions) {
    throw new VectorParseError(
      `This column is vector<float, ${expectedDimensions}>, but the pasted vector has ${values.length} value${
        values.length === 1 ? '' : 's'
      }.`,
    );
  }
  return values;
}

/* -------------------------------------------------------------------- rendering */

/** Down-samples to at most `samples` points, keeping the shape of the curve. */
export function downsample(values: readonly number[], samples = SPARKLINE_SAMPLES): number[] {
  if (values.length === 0) return [];
  const target = Math.max(1, samples);
  if (values.length <= target) return [...values];

  const sampled: number[] = [];
  for (let i = 0; i < target; i += 1) {
    const index = Math.min(
      values.length - 1,
      Math.floor((i * values.length + values.length / 2) / target),
    );
    const value = values[index];
    if (value !== undefined) sampled.push(value);
  }
  return sampled;
}

/**
 * An SVG path for the sparkline, normalised into the given box.
 *
 * A flat vector (every value identical) would divide by zero on the range, so it is drawn as a
 * centre line rather than collapsing to the top edge.
 */
export function sparklinePath(
  values: readonly number[],
  width: number,
  height: number,
  samples = SPARKLINE_SAMPLES,
): string {
  const points = downsample(values, samples);
  if (points.length === 0) return '';

  const min = Math.min(...points);
  const max = Math.max(...points);
  const range = max - min;
  const step = points.length === 1 ? 0 : width / (points.length - 1);

  return points
    .map((value, index) => {
      const x = index * step;
      const y = range === 0 ? height / 2 : height - ((value - min) / range) * height;
      return `${index === 0 ? 'M' : 'L'}${x.toFixed(2)},${y.toFixed(2)}`;
    })
    .join(' ');
}

/** `vector<float, 1536>` → the badge text `1536d`. */
export function dimensionBadge(dimensions: number): string {
  return `${dimensions}d`;
}

/** A short preview for tooltips and narrow cells: first few values, then an ellipsis. */
export function abbreviateVector(values: readonly number[], shown = 4, digits = 4): string {
  if (values.length === 0) return '[]';
  const head = values
    .slice(0, shown)
    .map((value) => formatComponent(value, digits))
    .join(', ');
  return values.length <= shown ? `[${head}]` : `[${head}, … ${values.length} total]`;
}

/** Trims trailing zeros so a column of components does not read as noise. */
export function formatComponent(value: number, digits = 6): string {
  if (!Number.isFinite(value)) return String(value);
  if (Number.isInteger(value)) return value.toString();
  return Number.parseFloat(value.toFixed(digits)).toString();
}

/** Export encoding for CSV/JSON — a JSON array of numbers, per plan §6. */
export function toJsonArray(values: readonly number[]): string {
  return `[${values.join(', ')}]`;
}

/* ------------------------------------------------------------------- arithmetic */

/**
 * Client-side mirrors of Cassandra's `similarity_*` functions, used for instant feedback in the
 * inspector before (or without) a server round trip.
 *
 * The normalisation matches the cluster exactly — cosine and dot product map into `[0, 1]` as
 * `(1 + raw) / 2`, euclidean as `1 / (1 + d²)`. A "prettier" formula here would show the user a
 * number they cannot reproduce in cqlsh.
 */
export function magnitude(values: readonly number[]): number {
  return Math.sqrt(values.reduce((sum, value) => sum + value * value, 0));
}

export function dot(a: readonly number[], b: readonly number[]): number {
  requireSameDimensions(a, b);
  // `?? 0` is unreachable after requireSameDimensions; it exists because noUncheckedIndexedAccess
  // types every index read as possibly undefined, and a non-null assertion would hide a real bug.
  return a.reduce((sum, value, index) => sum + value * (b[index] ?? 0), 0);
}

export function similarity(
  a: readonly number[],
  b: readonly number[],
  fn: SimilarityFunction,
): number {
  requireSameDimensions(a, b);
  switch (fn) {
    case 'cosine': {
      const magnitudes = magnitude(a) * magnitude(b);
      return (1 + (magnitudes === 0 ? 0 : dot(a, b) / magnitudes)) / 2;
    }
    case 'dot_product':
      return (1 + dot(a, b)) / 2;
    case 'euclidean': {
      const squared = a.reduce((sum, value, index) => sum + (value - (b[index] ?? 0)) ** 2, 0);
      return 1 / (1 + squared);
    }
    default:
      throw new VectorParseError(`Unknown similarity function ${String(fn)}.`);
  }
}

function requireSameDimensions(a: readonly number[], b: readonly number[]): void {
  if (a.length !== b.length) {
    throw new VectorParseError(
      `Dimension mismatch: ${a.length} vs ${b.length}. Similarity is only defined between vectors of equal length.`,
    );
  }
}

/* --------------------------------------------------------------- capability gate */

export interface CapabilityState {
  supported: boolean;
  /** `PARTIAL` features are shown WITH the caveat rather than hidden (contract wording). */
  partial: boolean;
  /** Shown verbatim as the tooltip. Never invent copy here — the probe explains itself. */
  reason: string;
}

const FALLBACK_REASON: Record<CapabilityName | string, string> = {
  vector: 'Vector columns and ANN queries require Cassandra 5.x or Astra.',
  sai: 'SAI indexes require Cassandra 5.x, DSE 6.8+ or Astra.',
};

/**
 * Resolves one capability from the probe.
 *
 * An absent probe means "we do not know yet", which is treated as unsupported: showing an ANN
 * builder that throws a syntax error on Keyspaces is worse than hiding it for a moment.
 */
export function capabilityState(
  capabilities: ClusterCapabilities | undefined | null,
  name: CapabilityName,
): CapabilityState {
  const capability = capabilities?.capabilities?.[name];
  const reason = capability?.reason ?? FALLBACK_REASON[name] ?? 'Unsupported on this cluster.';

  if (!capability) {
    return { supported: false, partial: false, reason };
  }
  return {
    supported: capability.support === 'SUPPORTED' || capability.support === 'PARTIAL',
    partial: capability.support === 'PARTIAL',
    reason,
  };
}

export function supportsVectorAnn(capabilities: ClusterCapabilities | undefined | null): boolean {
  return capabilityState(capabilities, 'vector').supported;
}

export function supportsSai(capabilities: ClusterCapabilities | undefined | null): boolean {
  return capabilityState(capabilities, 'sai').supported;
}

/* ------------------------------------------------------------- ANN request build */

export interface AnnBuilderState {
  keyspace: string;
  table: string;
  column: VectorColumn | null;
  /** Literal values, when the user pasted or uploaded a vector. */
  values: number[] | null;
  /** Complete primary key of a reference row — "find rows similar to this one". */
  fromRow: Record<string, unknown> | null;
  limit: number;
  selectColumns: string[];
  predicates: AnnPredicate[];
  similarityProjections: SimilarityFunction[];
  includeVectorColumn: boolean;
  fetchSize: number;
}

export const DEFAULT_LIMIT = 10;
export const MAX_LIMIT = 10_000;
/** Contract default. ANN returns at most k rows, so this only matters for very large k. */
export const DEFAULT_FETCH_SIZE = 500;

export function emptyBuilderState(keyspace = '', table = ''): AnnBuilderState {
  return {
    keyspace,
    table,
    column: null,
    values: null,
    fromRow: null,
    limit: DEFAULT_LIMIT,
    selectColumns: [],
    predicates: [],
    similarityProjections: ['cosine'],
    includeVectorColumn: false,
    fetchSize: DEFAULT_FETCH_SIZE,
  };
}

/**
 * Why the Run button is disabled, or `null` when it is not.
 *
 * Returned as text rather than a boolean so the UI can say what is missing — "pick a column" is
 * actionable, a greyed-out button is not.
 */
export function annBuilderProblem(state: AnnBuilderState): string | null {
  if (!state.keyspace || !state.table) return 'Choose a table.';
  if (!state.column) return 'Choose a vector column.';
  if (!state.column.index) {
    return `${state.column.name} has no SAI index, so ANN is not available on it yet.`;
  }
  if (!state.values && !state.fromRow) {
    return 'Paste a query vector, or reference an existing row.';
  }
  if (state.values && state.values.length !== state.column.dimensions) {
    return `The query vector has ${state.values.length} values but ${state.column.name} is vector<float, ${state.column.dimensions}>.`;
  }
  if (!Number.isInteger(state.limit) || state.limit < 1 || state.limit > MAX_LIMIT) {
    return `LIMIT must be a whole number between 1 and ${MAX_LIMIT}.`;
  }
  return null;
}

/** Maps builder state onto the contract's `AnnQueryRequest`. Throws if the state is incomplete. */
export function buildAnnRequest(state: AnnBuilderState): AnnQueryRequest {
  const problem = annBuilderProblem(state);
  if (problem) throw new VectorParseError(problem);

  const column = state.column as VectorColumn;
  return {
    keyspace: state.keyspace,
    table: state.table,
    vectorColumn: column.name,
    queryVector: state.values
      ? { values: state.values }
      : { fromRow: { primaryKey: state.fromRow as Record<string, never>, column: column.name } },
    limit: state.limit,
    selectColumns: state.selectColumns.length > 0 ? state.selectColumns : undefined,
    predicates: state.predicates.length > 0 ? state.predicates : undefined,
    similarityProjections:
      state.similarityProjections.length > 0 ? state.similarityProjections : undefined,
    includeVectorColumn: state.includeVectorColumn,
    fetchSize: state.fetchSize,
  };
}

/** Score column name generated for a similarity projection, e.g. `cosine_score`. */
export function scoreColumnName(fn: SimilarityFunction): string {
  return `${fn}_score`;
}

/**
 * Flags the score columns in a result set so the grid can render them as a sortable score rather
 * than an anonymous float.
 */
export function isScoreColumn(name: string, projections: readonly SimilarityFunction[]): boolean {
  return projections.some((fn) => scoreColumnName(fn) === name);
}
