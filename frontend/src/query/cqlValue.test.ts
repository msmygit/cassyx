import { describe, expect, it } from 'vitest';
import {
  base64ToBytes,
  blobToHex,
  bytesToHex,
  cellState,
  compareValues,
  columnNames,
  cqlKind,
  isUnset,
  parseEditorValue,
  renderAnnotation,
  renderValue,
  timeuuidTimestamp,
} from './cqlValue';
import type { ColumnMetadata } from './types';

const column = (type: string, extra: Partial<ColumnMetadata> = {}): ColumnMetadata =>
  ({ name: 'c', type, ...extra }) as ColumnMetadata;

describe('cqlKind', () => {
  it('classifies scalars, collections and structured types', () => {
    expect(cqlKind(column('text'))).toBe('text');
    expect(cqlKind(column('varchar'))).toBe('text');
    expect(cqlKind(column('bigint'))).toBe('bignum');
    expect(cqlKind(column('varint'))).toBe('bignum');
    expect(cqlKind(column('decimal'))).toBe('bignum');
    expect(cqlKind(column('counter'))).toBe('counter');
    expect(cqlKind(column('int'))).toBe('number');
    expect(cqlKind(column('double'))).toBe('number');
    expect(cqlKind(column('boolean'))).toBe('boolean');
    expect(cqlKind(column('uuid'))).toBe('uuid');
    expect(cqlKind(column('timeuuid'))).toBe('timeuuid');
    expect(cqlKind(column('timestamp'))).toBe('timestamp');
    expect(cqlKind(column('date'))).toBe('date');
    expect(cqlKind(column('time'))).toBe('time');
    expect(cqlKind(column('duration'))).toBe('duration');
    expect(cqlKind(column('inet'))).toBe('inet');
    expect(cqlKind(column('blob'))).toBe('blob');
    expect(cqlKind(column('list<int>'))).toBe('list');
    expect(cqlKind(column('set<text>'))).toBe('set');
    expect(cqlKind(column('map<text, int>'))).toBe('map');
    expect(cqlKind(column('tuple<int, text>'))).toBe('tuple');
    expect(cqlKind(column('vector<float, 3>'))).toBe('vector');
    expect(cqlKind(column('frozen<list<int>>'))).toBe('list');
    expect(cqlKind(column('address', { udt: true }))).toBe('udt');
    expect(cqlKind(column('somethingelse'))).toBe('unknown');
    expect(cqlKind(undefined)).toBe('unknown');
  });
});

describe('null vs unset', () => {
  it('distinguishes an absent key, an explicit unset and a null', () => {
    expect(cellState({ a: 1 }, 'b')).toBe('unset');
    expect(cellState({ b: '$unset' }, 'b')).toBe('unset');
    expect(cellState({ b: null }, 'b')).toBe('null');
    expect(cellState({ b: 1 }, 'b')).toBe('value');
    expect(cellState(undefined, 'b')).toBe('unset');
    expect(isUnset('$unset')).toBe(true);
    expect(isUnset(null)).toBe(false);
  });

  it('renders them differently, because Cassandra treats them differently', () => {
    expect(renderValue('$unset')).toBe('⊘ unset');
    expect(renderValue(null)).toBe('null');
    expect(renderValue(undefined)).toBe('null');
  });
});

describe('big numerics', () => {
  it('never coerces a bigint to a JS number', () => {
    // 2^53 + 1 — the first integer a JSON number cannot hold.
    const value = '9007199254740993';
    expect(renderValue(value, column('bigint'))).toBe('9007199254740993');
    expect(parseEditorValue(value, column('bigint'))).toBe('9007199254740993');
    expect(parseEditorValue(value, column('counter'))).toBe('9007199254740993');
    // The bug this guards against:
    expect(String(Number(value))).not.toBe(value);
  });

  it('sorts big numerics numerically, not lexically', () => {
    expect(compareValues('9', '10', 'bignum')).toBeLessThan(0);
    // The naive alternative — plain lexical ordering — gets this backwards.
    expect('9'.localeCompare('10')).toBeGreaterThan(0);
    expect(compareValues('9007199254740993', '9007199254740992', 'bignum')).toBeGreaterThan(0);
    expect(compareValues('not-a-number', 'x', 'bignum')).toBeLessThan(0);
  });
});

describe('blobs', () => {
  it('round-trips base64 to hex', () => {
    expect(bytesToHex(base64ToBytes('YWI='))).toBe('6162');
    expect(blobToHex('YWI=')).toBe('0x6162');
    expect(renderValue('YWI=', column('blob'))).toBe('0x6162');
    expect(renderValue('YWI=', column('blob'), { blobFormat: 'base64' })).toBe('YWI=');
    expect(renderAnnotation('YWI=', column('blob'))).toBe('2 bytes');
  });
});

describe('timeuuid', () => {
  it('decodes the embedded timestamp of a v1 uuid', () => {
    // Known v1 UUID with time 2013-07-04T21:14:40.084Z.
    const decoded = timeuuidTimestamp('a5c4d4d0-e4ff-11e2-a8ef-9b8cd3f3a4dc');
    expect(decoded).toBeInstanceOf(Date);
    expect(decoded?.getUTCFullYear()).toBe(2013);
    expect(renderAnnotation('a5c4d4d0-e4ff-11e2-a8ef-9b8cd3f3a4dc', column('timeuuid'))).toContain(
      '2013',
    );
  });

  it('returns null for a v4 uuid or a malformed one', () => {
    expect(timeuuidTimestamp('1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d')).toBeNull();
    expect(timeuuidTimestamp('nonsense')).toBeNull();
    expect(renderAnnotation('1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d', column('timeuuid'))).toBeNull();
  });
});

describe('structured rendering', () => {
  it('renders collections, maps and vectors', () => {
    expect(renderValue([1, 2, 3], column('list<int>'))).toBe('[1, 2, 3]');
    expect(renderValue({ a: 1 }, column('map<text, int>'))).toBe('{a: 1}');
    expect(renderValue([0.1, 0.2], column('vector<float, 2>', { vector: true }))).toBe(
      '[0.1, 0.2]',
    );
    expect(
      renderValue([0.1, 0.2], column('vector<float, 2>', { vector: true }), { compact: true }),
    ).toBe('vector[2]');
    expect(renderAnnotation([0.1, 0.2], column('vector<float, 2>', { vector: true }))).toBe(
      '2 dimensions',
    );
  });

  it('truncates long collections in compact mode', () => {
    const long = Array.from({ length: 12 }, (_, i) => i);
    expect(renderValue(long, column('list<int>'), { compact: true })).toContain('+4');
  });
});

describe('parseEditorValue', () => {
  it('understands the null and unset sentinels', () => {
    expect(parseEditorValue('null', column('text'))).toBeNull();
    expect(parseEditorValue('$unset', column('text'))).toBe('$unset');
    expect(parseEditorValue('⊘ unset', column('text'))).toBe('$unset');
  });

  it('parses per type and rejects nonsense loudly', () => {
    expect(parseEditorValue('42', column('int'))).toBe(42);
    expect(parseEditorValue('true', column('boolean'))).toBe(true);
    expect(parseEditorValue('false', column('boolean'))).toBe(false);
    expect(parseEditorValue('[1,2]', column('list<int>'))).toEqual([1, 2]);
    expect(parseEditorValue('hello', column('text'))).toBe('hello');
    expect(() => parseEditorValue('nope', column('int'))).toThrow(/not a valid/);
    expect(() => parseEditorValue('nope', column('boolean'))).toThrow(/not a boolean/);
    expect(() => parseEditorValue('nope', column('list<int>'))).toThrow(/not valid JSON/);
  });
});

describe('columnNames', () => {
  it('preserves projection order', () => {
    expect(columnNames([column('int'), { name: 'b', type: 'text' } as ColumnMetadata])).toEqual([
      'c',
      'b',
    ]);
  });
});

describe('compareValues', () => {
  it('sorts nulls and unsets last', () => {
    expect(compareValues(null, 1, 'number')).toBeGreaterThan(0);
    expect(compareValues(1, null, 'number')).toBeLessThan(0);
    expect(compareValues('$unset', 1, 'number')).toBeGreaterThan(0);
    expect(compareValues(1, 1, 'number')).toBe(0);
    expect(compareValues(true, false, 'boolean')).toBeGreaterThan(0);
  });
});
