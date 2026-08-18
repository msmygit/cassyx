import { describe, expect, it } from 'vitest';
import type { ClusterCapabilities, VectorColumn } from './types';
import {
  abbreviateVector,
  annBuilderProblem,
  buildAnnRequest,
  capabilityState,
  dimensionBadge,
  DEFAULT_LIMIT,
  dot,
  downsample,
  emptyBuilderState,
  formatComponent,
  isScoreColumn,
  magnitude,
  parseVectorText,
  scoreColumnName,
  similarity,
  sparklinePath,
  supportsSai,
  supportsVectorAnn,
  toJsonArray,
  VectorParseError,
  type AnnBuilderState,
} from './vectorModel';

const INDEXED_COLUMN: VectorColumn = {
  identity: { kind: 'COLUMN', keyspace: 'demo', table: 'doc_embeddings', column: 'embedding' },
  name: 'embedding',
  dimensions: 3,
  elementType: 'float',
  cqlType: 'vector<float, 3>',
  index: {
    identity: { kind: 'INDEX', keyspace: 'demo', table: 'doc_embeddings', index: 'ann' },
    name: 'ann',
    target: 'embedding',
    options: {},
  },
  similarityFunction: 'cosine',
};

const UNINDEXED_COLUMN: VectorColumn = { ...INDEXED_COLUMN, index: null };

function capabilities(
  vector: 'SUPPORTED' | 'UNSUPPORTED' | 'PARTIAL',
  sai: 'SUPPORTED' | 'UNSUPPORTED' | 'PARTIAL',
  reason = 'Vector columns and ANN queries require Cassandra 5.x or Astra. This cluster reports ScyllaDB 6.0.',
): ClusterCapabilities {
  return {
    flavour: 'SCYLLA',
    probedAt: '2026-08-18T00:00:00Z',
    capabilities: {
      vector: { name: 'vector', support: vector, reason },
      sai: { name: 'sai', support: sai, reason },
    },
  } as ClusterCapabilities;
}

describe('parseVectorText', () => {
  it('accepts JSON arrays, bracketed lists and bare numbers', () => {
    expect(parseVectorText('[0.1, 0.2, 0.3]')).toEqual([0.1, 0.2, 0.3]);
    expect(parseVectorText('0.1 0.2 0.3')).toEqual([0.1, 0.2, 0.3]);
    expect(parseVectorText('0.1,0.2,0.3')).toEqual([0.1, 0.2, 0.3]);
    expect(parseVectorText('[\n  -1e-3,\n  2\n]')).toEqual([-0.001, 2]);
  });

  it('names the offending token rather than failing generically', () => {
    expect(() => parseVectorText('[0.1, oops, 0.3]')).toThrow(VectorParseError);
    expect(() => parseVectorText('[0.1, oops, 0.3]')).toThrow(/"oops" at position 2/);
  });

  it('rejects empty input', () => {
    expect(() => parseVectorText('  ')).toThrow(/Paste a vector first/);
    expect(() => parseVectorText('[]')).toThrow(/empty/);
  });

  it('checks the dimension against the column so the 400 never happens', () => {
    expect(parseVectorText('[1, 2, 3]', 3)).toHaveLength(3);
    expect(() => parseVectorText('[1, 2]', 1536)).toThrow(/vector<float, 1536>.*2 values/s);
    expect(() => parseVectorText('[1]', 1536)).toThrow(/1 value\./);
  });
});

describe('sparkline rendering', () => {
  it('down-samples a 1536-dimension vector to the sparkline resolution', () => {
    const values = Array.from({ length: 1536 }, (_, i) => i);
    const sampled = downsample(values);

    expect(sampled).toHaveLength(64);
    expect(sampled[0]).toBe(12);
    expect(sampled.at(-1)).toBe(1524);
  });

  it('returns short vectors untouched', () => {
    expect(downsample([1, 2, 3])).toEqual([1, 2, 3]);
    expect(downsample([])).toEqual([]);
    expect(downsample([1, 2, 3, 4], 0)).toHaveLength(1);
  });

  it('builds an SVG path inside the box', () => {
    const path = sparklinePath([0, 1], 100, 20);
    expect(path).toBe('M0.00,20.00 L100.00,0.00');
  });

  it('draws a flat vector as a centre line instead of dividing by zero', () => {
    expect(sparklinePath([5, 5, 5], 100, 20)).toBe('M0.00,10.00 L50.00,10.00 L100.00,10.00');
    expect(sparklinePath([], 100, 20)).toBe('');
    expect(sparklinePath([7], 100, 20)).toBe('M0.00,10.00');
  });

  it('formats the dimension badge and abbreviations', () => {
    expect(dimensionBadge(1536)).toBe('1536d');
    expect(abbreviateVector([0.1, 0.2])).toBe('[0.1, 0.2]');
    expect(abbreviateVector([1, 2, 3, 4, 5, 6])).toBe('[1, 2, 3, 4, … 6 total]');
    expect(abbreviateVector([])).toBe('[]');
    expect(toJsonArray([1, 2])).toBe('[1, 2]');
  });

  it('trims float noise without lying about integers', () => {
    expect(formatComponent(0.1 + 0.2)).toBe('0.3');
    expect(formatComponent(3)).toBe('3');
    expect(formatComponent(Number.NaN)).toBe('NaN');
  });
});

describe('similarity arithmetic', () => {
  it('mirrors Cassandra normalisation exactly', () => {
    // These are the same expectations the backend asserts against a live cluster in AnnQueryIT.
    expect(similarity([1, 0], [1, 0], 'cosine')).toBeCloseTo(1, 9);
    expect(similarity([1, 0], [-1, 0], 'cosine')).toBeCloseTo(0, 9);
    expect(similarity([1, 0], [0, 1], 'cosine')).toBeCloseTo(0.5, 9);
    expect(similarity([3, 4], [1, 0], 'dot_product')).toBeCloseTo(2, 9);
    expect(similarity([0, 0], [3, 4], 'euclidean')).toBeCloseTo(1 / 26, 9);
  });

  it('does not divide by zero on a zero vector', () => {
    expect(similarity([0, 0], [1, 1], 'cosine')).toBeCloseTo(0.5, 9);
  });

  it('computes magnitude and dot product', () => {
    expect(magnitude([3, 4])).toBe(5);
    expect(magnitude([])).toBe(0);
    expect(dot([1, 2], [3, 4])).toBe(11);
  });

  it('refuses mismatched dimensions rather than truncating', () => {
    expect(() => similarity([1], [1, 2], 'cosine')).toThrow(/Dimension mismatch: 1 vs 2/);
    expect(() => dot([1], [1, 2])).toThrow(VectorParseError);
  });

  it('rejects an unknown function', () => {
    expect(() => similarity([1], [1], 'manhattan' as never)).toThrow(/Unknown similarity function/);
  });
});

describe('capability gating (plan §7.1)', () => {
  it('hides vector and SAI on ScyllaDB and Keyspaces, with the probe reason', () => {
    const caps = capabilities('UNSUPPORTED', 'UNSUPPORTED');

    expect(supportsVectorAnn(caps)).toBe(false);
    expect(supportsSai(caps)).toBe(false);
    expect(capabilityState(caps, 'vector').reason).toContain('ScyllaDB 6.0');
  });

  it('keeps SAI on DSE while hiding vector/ANN', () => {
    const caps = capabilities('UNSUPPORTED', 'SUPPORTED');
    expect(supportsVectorAnn(caps)).toBe(false);
    expect(supportsSai(caps)).toBe(true);
  });

  it('shows PARTIAL features with the caveat rather than hiding them', () => {
    const state = capabilityState(capabilities('PARTIAL', 'PARTIAL'), 'vector');
    expect(state.supported).toBe(true);
    expect(state.partial).toBe(true);
  });

  it('treats an absent probe as unsupported and still explains why', () => {
    expect(supportsVectorAnn(undefined)).toBe(false);
    expect(capabilityState(null, 'vector').reason).toContain('Cassandra 5.x or Astra');
    expect(capabilityState(null, 'sai').reason).toContain('DSE 6.8+');
    expect(capabilityState({ capabilities: {} } as ClusterCapabilities, 'truncate').reason).toBe(
      'Unsupported on this cluster.',
    );
  });
});

describe('ANN request building', () => {
  function state(overrides: Partial<AnnBuilderState> = {}): AnnBuilderState {
    return {
      ...emptyBuilderState('demo', 'doc_embeddings'),
      column: INDEXED_COLUMN,
      values: [0.1, 0.2, 0.3],
      ...overrides,
    };
  }

  it('explains what is missing instead of just disabling the button', () => {
    expect(annBuilderProblem(emptyBuilderState())).toBe('Choose a table.');
    expect(annBuilderProblem(state({ column: null }))).toBe('Choose a vector column.');
    expect(annBuilderProblem(state({ column: UNINDEXED_COLUMN }))).toContain('no SAI index');
    expect(annBuilderProblem(state({ values: null }))).toContain('Paste a query vector');
    expect(annBuilderProblem(state({ values: [0.1] }))).toContain('vector<float, 3>');
    expect(annBuilderProblem(state({ limit: 0 }))).toContain('between 1 and 10000');
    expect(annBuilderProblem(state({ limit: 10001 }))).toContain('between 1 and 10000');
    expect(annBuilderProblem(state())).toBeNull();
  });

  it('maps builder state onto the contract request', () => {
    const request = buildAnnRequest(
      state({
        limit: 3,
        selectColumns: ['doc_id', 'title'],
        predicates: [{ column: 'category', operator: '=', value: 'runbook' }],
        similarityProjections: ['cosine'],
      }),
    );

    expect(request).toEqual({
      keyspace: 'demo',
      table: 'doc_embeddings',
      vectorColumn: 'embedding',
      queryVector: { values: [0.1, 0.2, 0.3] },
      limit: 3,
      selectColumns: ['doc_id', 'title'],
      predicates: [{ column: 'category', operator: '=', value: 'runbook' }],
      similarityProjections: ['cosine'],
      includeVectorColumn: false,
      fetchSize: 500,
    });
  });

  it('supports referencing an existing row instead of pasting values', () => {
    const request = buildAnnRequest(
      state({ values: null, fromRow: { doc_id: 'abc', chunk_no: 0 } }),
    );

    expect(request.queryVector).toEqual({
      fromRow: { primaryKey: { doc_id: 'abc', chunk_no: 0 }, column: 'embedding' },
    });
  });

  it('omits empty optional arrays rather than sending []', () => {
    const request = buildAnnRequest(state({ similarityProjections: [] }));
    expect(request.selectColumns).toBeUndefined();
    expect(request.predicates).toBeUndefined();
    expect(request.similarityProjections).toBeUndefined();
    expect(request.limit).toBe(DEFAULT_LIMIT);
  });

  it('throws rather than sending an invalid request', () => {
    expect(() => buildAnnRequest(state({ values: null }))).toThrow(VectorParseError);
  });

  it('names score columns the way the generator does', () => {
    expect(scoreColumnName('dot_product')).toBe('dot_product_score');
    expect(isScoreColumn('cosine_score', ['cosine'])).toBe(true);
    expect(isScoreColumn('title', ['cosine'])).toBe(false);
  });
});
