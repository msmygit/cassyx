import { describe, expect, it } from 'vitest';
import {
  applyResultView,
  matchesFilters,
  matchesSearch,
  nextSort,
  primaryKeyOf,
  projectsFullPrimaryKey,
  toCsv,
} from './resultView';
import type { ColumnMetadata, ResultRow } from './types';

const columns: ColumnMetadata[] = [
  {
    name: 'user_id',
    type: 'uuid',
    primaryKeyColumn: true,
    kind: 'PARTITION_KEY',
  } as ColumnMetadata,
  {
    name: 'created_at',
    type: 'timestamp',
    primaryKeyColumn: true,
    kind: 'CLUSTERING',
  } as ColumnMetadata,
  { name: 'logins', type: 'bigint', primaryKeyColumn: false, kind: 'REGULAR' } as ColumnMetadata,
];

const rows: ResultRow[] = [
  { user_id: 'a', created_at: '2026-01-01T00:00:00Z', logins: '10' },
  { user_id: 'b', created_at: '2026-01-02T00:00:00Z', logins: '9' },
  { user_id: 'c', created_at: '2026-01-03T00:00:00Z', logins: null },
];

describe('search and filter', () => {
  it('searches every rendered cell', () => {
    expect(matchesSearch(rows[0] as ResultRow, columns, '2026-01-01')).toBe(true);
    expect(matchesSearch(rows[0] as ResultRow, columns, 'nope')).toBe(false);
    expect(matchesSearch(rows[0] as ResultRow, columns, '   ')).toBe(true);
  });

  it('filters per column', () => {
    expect(matchesFilters(rows[0] as ResultRow, columns, { user_id: 'a' })).toBe(true);
    expect(matchesFilters(rows[0] as ResultRow, columns, { user_id: 'b' })).toBe(false);
    expect(matchesFilters(rows[0] as ResultRow, columns, { user_id: '  ' })).toBe(true);
  });
});

describe('applyResultView', () => {
  it('sorts a bigint column numerically even though it arrives as a string', () => {
    const sorted = applyResultView(rows, columns, { sort: { column: 'logins', direction: 'asc' } });
    expect(sorted.map((row) => row.logins)).toEqual(['9', '10', null]);
  });

  it('sorts descending and combines with search', () => {
    const sorted = applyResultView(rows, columns, {
      search: '2026-01',
      sort: { column: 'user_id', direction: 'desc' },
    });
    expect(sorted.map((row) => row.user_id)).toEqual(['c', 'b', 'a']);
  });

  it('applies per-column filters', () => {
    expect(applyResultView(rows, columns, { filters: { user_id: 'b' } })).toHaveLength(1);
  });

  it('is a no-op with an empty spec', () => {
    expect(applyResultView(rows, columns, {})).toBe(rows);
  });
});

describe('nextSort', () => {
  it('cycles none → asc → desc → none', () => {
    const first = nextSort(null, 'logins');
    expect(first).toEqual({ column: 'logins', direction: 'asc' });
    const second = nextSort(first, 'logins');
    expect(second).toEqual({ column: 'logins', direction: 'desc' });
    expect(nextSort(second, 'logins')).toBeNull();
    expect(nextSort(second, 'user_id')).toEqual({ column: 'user_id', direction: 'asc' });
  });
});

describe('primary key rules', () => {
  it('recognises a result set that projects the whole key', () => {
    expect(projectsFullPrimaryKey(columns)).toBe(true);
    expect(projectsFullPrimaryKey([columns[2] as ColumnMetadata])).toBe(false);
  });

  it('extracts the WHERE key, or names what is missing', () => {
    expect(primaryKeyOf(rows[0] as ResultRow, columns)).toEqual({
      key: { user_id: 'a', created_at: '2026-01-01T00:00:00Z' },
    });
    expect(primaryKeyOf({ user_id: 'a' }, columns)).toEqual({ missing: ['created_at'] });
    expect(primaryKeyOf({}, [columns[2] as ColumnMetadata])).toEqual({
      missing: ['<unknown primary key>'],
    });
  });
});

describe('toCsv', () => {
  it('quotes separators, quotes and newlines', () => {
    const csv = toCsv([{ user_id: 'a,b', created_at: 'x"y', logins: '1' }], columns);
    expect(csv.split('\n')[0]).toBe('user_id,created_at,logins');
    expect(csv).toContain('"a,b"');
    expect(csv).toContain('"x""y"');
  });
});
