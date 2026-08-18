import { describe, expect, it } from 'vitest';
import {
  collectExpandableIds,
  filterTree,
  findNode,
  isSystemKeyspace,
  nodeId,
  parseDragPayload,
  qualifiedName,
  quoteIdentifier,
  statementForNode,
  toDragPayload,
  type SchemaNode,
} from './model';
import { placeholderCatalog } from './placeholderCatalog';

function node(partial: Partial<SchemaNode> & Pick<SchemaNode, 'kind' | 'identity'>): SchemaNode {
  return {
    id: nodeId(partial.kind, partial.identity),
    label: partial.identity.column ?? partial.identity.table ?? partial.identity.keyspace,
    system: isSystemKeyspace(partial.identity.keyspace),
    ...partial,
  } as SchemaNode;
}

describe('identity resolution — regression guard for the prior-art keyspace bug', () => {
  it('builds the statement from the node’s own identity, not its position in the tree', () => {
    const catalog = placeholderCatalog();
    // Both keyspaces contain a table literally named `users`.
    const demoUsers = findNode(catalog, nodeId('TABLE', { keyspace: 'demo', table: 'users' }));
    const systemUsers = findNode(
      catalog,
      nodeId('TABLE', { keyspace: 'system_auth', table: 'users' }),
    );

    expect(demoUsers).not.toBeNull();
    expect(systemUsers).not.toBeNull();

    expect(statementForNode(demoUsers!, { limit: 500 })).toBe(
      'SELECT * FROM demo.users LIMIT 500;',
    );
    expect(statementForNode(systemUsers!, { limit: 500 })).toBe(
      'SELECT * FROM system_auth.users LIMIT 500;',
    );
    // The exact prior-art failure: dragging demo.users must never yield system_auth.users.
    expect(statementForNode(demoUsers!)).not.toContain('system_auth');
  });

  it('carries the full identity on the drag payload and round-trips it', () => {
    const table = node({ kind: 'TABLE', identity: { keyspace: 'demo', table: 'users' } });
    const payload = toDragPayload(table, { limit: 100 });

    expect(payload.identity).toEqual({ keyspace: 'demo', table: 'users' });
    expect(payload.statement).toBe('SELECT * FROM demo.users LIMIT 100;');

    const parsed = parseDragPayload(JSON.stringify(payload));
    expect(parsed?.identity.keyspace).toBe('demo');
    expect(parseDragPayload('not json')).toBeNull();
    expect(parseDragPayload('{"kind":"table"}')).toBeNull();
  });

  it('omits the LIMIT clause when no limit is supplied — no hardcoded LIMIT 100', () => {
    const table = node({ kind: 'TABLE', identity: { keyspace: 'demo', table: 'orders' } });
    expect(statementForNode(table)).toBe('SELECT * FROM demo.orders;');
  });

  it('produces sensible statements for the other node kinds', () => {
    expect(statementForNode(node({ kind: 'KEYSPACE', identity: { keyspace: 'demo' } }))).toBe(
      'USE demo;',
    );
    expect(
      statementForNode(
        node({ kind: 'COLUMN', identity: { keyspace: 'demo', table: 'users', column: 'email' } }),
      ),
    ).toBe('email');
    expect(
      statementForNode(node({ kind: 'VIEW', identity: { keyspace: 'demo', table: 'v1' } })),
    ).toBe('SELECT * FROM demo.v1;');
  });
});

describe('quoteIdentifier', () => {
  it('leaves plain lower-case identifiers alone', () => {
    expect(quoteIdentifier('users')).toBe('users');
    expect(quoteIdentifier('_x9')).toBe('_x9');
  });

  it('quotes mixed case, reserved words and awkward characters', () => {
    expect(quoteIdentifier('MyTable')).toBe('"MyTable"');
    expect(quoteIdentifier('select')).toBe('"select"');
    expect(quoteIdentifier('with space')).toBe('"with space"');
    expect(quoteIdentifier('we"ird')).toBe('"we""ird"');
  });

  it('qualifies names correctly', () => {
    expect(qualifiedName({ keyspace: 'demo', table: 'users' })).toBe('demo.users');
    expect(qualifiedName({ keyspace: 'demo' })).toBe('demo');
    expect(qualifiedName({ keyspace: 'My KS', table: 'Order' })).toBe('"My KS"."Order"');
  });
});

describe('isSystemKeyspace', () => {
  it('recognises the known and prefixed system keyspaces', () => {
    expect(isSystemKeyspace('system')).toBe(true);
    expect(isSystemKeyspace('system_auth')).toBe(true);
    expect(isSystemKeyspace('SYSTEM_SCHEMA')).toBe(true);
    expect(isSystemKeyspace('dse_perf')).toBe(true);
    expect(isSystemKeyspace('demo')).toBe(false);
    expect(isSystemKeyspace('systems_of_record')).toBe(false);
  });
});

describe('filterTree', () => {
  const catalog = placeholderCatalog();

  it('hides system keyspaces unless the toggle is on', () => {
    const hidden = filterTree(catalog, { search: '', showSystem: false });
    expect(hidden.map((n) => n.label)).toEqual(['demo', 'analytics']);

    const shown = filterTree(catalog, { search: '', showSystem: true });
    expect(shown.map((n) => n.label)).toContain('system_auth');
  });

  it('keeps ancestors of a matching descendant', () => {
    const result = filterTree(catalog, { search: 'embedding', showSystem: false });
    expect(result).toHaveLength(1);
    expect(result[0]?.label).toBe('demo');
    expect(result[0]?.children?.map((n) => n.label)).toEqual(['product_embeddings']);
  });

  it('keeps the whole subtree when the node itself matches', () => {
    const result = filterTree(catalog, { search: 'orders', showSystem: false });
    const demo = result.find((n) => n.label === 'demo');
    const orders = demo?.children?.find((n) => n.label === 'orders');
    expect(orders?.children?.length).toBeGreaterThan(0);
  });

  it('still respects the system filter while searching', () => {
    const result = filterTree(catalog, { search: 'users', showSystem: false });
    expect(JSON.stringify(result)).not.toContain('system_auth');
  });

  it('returns nothing for a non-matching search', () => {
    expect(filterTree(catalog, { search: 'zzzz', showSystem: true })).toEqual([]);
  });
});

describe('tree helpers', () => {
  it('collects expandable branch ids', () => {
    const ids = collectExpandableIds(
      filterTree(placeholderCatalog(), { search: '', showSystem: false }),
    );
    expect(ids.length).toBeGreaterThan(0);
    expect(ids).toContain(nodeId('KEYSPACE', { keyspace: 'demo' }));
  });

  it('returns null for an unknown node id', () => {
    expect(findNode(placeholderCatalog(), 'nope')).toBeNull();
  });
});
