import { describe, expect, it } from 'vitest';
import { toIdentity, toTree, toTreeNode } from './fromApi';
import { qualifiedName, statementForNode } from './model';
import type { ApiSchemaNode, ApiSchemaTree } from './schemaTypes';

function node(overrides: Partial<ApiSchemaNode> & Pick<ApiSchemaNode, 'identity'>): ApiSchemaNode {
  return {
    label: 'x',
    kind: 'TABLE',
    ...overrides,
  } as ApiSchemaNode;
}

describe('fromApi', () => {
  it('copies every identity field across without inferring any of them', () => {
    expect(
      toIdentity({
        kind: 'AGGREGATE',
        keyspace: 'demo',
        name: 'average',
        signature: '(double)',
      }),
    ).toEqual({ keyspace: 'demo', name: 'average', signature: '(double)' });

    expect(toIdentity({ kind: 'KEYSPACE', keyspace: 'demo' })).toEqual({ keyspace: 'demo' });

    expect(
      toIdentity({ kind: 'INDEX', keyspace: 'demo', table: 'users', index: 'users_email_idx' }),
    ).toEqual({ keyspace: 'demo', table: 'users', index: 'users_email_idx' });

    expect(toIdentity({ kind: 'VIEW', keyspace: 'demo', view: 'users_by_email' })).toEqual({
      keyspace: 'demo',
      view: 'users_by_email',
    });
  });

  it('parses the column detail line into a type, kind and vector dimension', () => {
    const column = toTreeNode(
      node({
        identity: { kind: 'COLUMN', keyspace: 'demo', table: 'users', column: 'embedding' },
        label: 'embedding',
        kind: 'COLUMN',
        detail: 'vector<float, 1536> | REGULAR',
      }),
    );

    expect(column.dataType).toBe('vector<float, 1536>');
    expect(column.columnKind).toBe('REGULAR');
    expect(column.vectorDimension).toBe(1536);
  });

  it('marks static and partition-key columns from the same detail line', () => {
    const staticColumn = toTreeNode(
      node({
        identity: { kind: 'COLUMN', keyspace: 'demo', table: 'users', column: 'tenant' },
        kind: 'COLUMN',
        label: 'tenant',
        detail: 'text | STATIC',
      }),
    );
    const partitionKey = toTreeNode(
      node({
        identity: { kind: 'COLUMN', keyspace: 'demo', table: 'users', column: 'user_id' },
        kind: 'COLUMN',
        label: 'user_id',
        detail: 'uuid | PARTITION_KEY',
      }),
    );

    expect(staticColumn.columnKind).toBe('STATIC');
    expect(partitionKey.columnKind).toBe('PARTITION_KEY');
    expect(partitionKey.vectorDimension).toBeNull();
  });

  it('ignores an unrecognised kind rather than mislabelling the column', () => {
    const column = toTreeNode(
      node({
        identity: { kind: 'COLUMN', keyspace: 'demo', table: 'users', column: 'x' },
        kind: 'COLUMN',
        label: 'x',
        detail: 'text | SOMETHING_NEW',
      }),
    );
    expect(column.columnKind).toBeUndefined();
    expect(column.dataType).toBe('text');
  });

  it('leaves non-column nodes without column metadata', () => {
    const table = toTreeNode(
      node({ identity: { kind: 'TABLE', keyspace: 'demo', table: 'users' }, detail: '12 columns' }),
    );
    expect(table.dataType).toBeUndefined();
    expect(table.columnKind).toBeUndefined();
  });

  it('nests children and derives a stable, collision-free id', () => {
    const tree: ApiSchemaTree = {
      connectionId: 'c1',
      generatedAt: '2026-08-17T10:31:00Z',
      keyspaces: [
        node({
          identity: { kind: 'KEYSPACE', keyspace: 'demo' },
          label: 'demo',
          kind: 'KEYSPACE',
          children: [
            node({
              identity: { kind: 'TABLE', keyspace: 'demo', table: 'users' },
              label: 'users',
              kind: 'TABLE',
            }),
            node({
              identity: { kind: 'TYPE', keyspace: 'demo', name: 'users' },
              label: 'users',
              kind: 'TYPE',
            }),
          ],
        }),
      ],
    };

    const [keyspace] = toTree(tree);
    expect(keyspace?.children).toHaveLength(2);
    expect(keyspace?.children?.[0]?.id).not.toBe(keyspace?.children?.[1]?.id);
    expect(keyspace?.children?.[1]?.identity.name).toBe('users');
  });

  /**
   * The regression the whole identity design exists for: two tables called `users` in different
   * keyspaces must produce different statements no matter where they sit in the tree.
   */
  it('never resolves demo.users to system_auth.users', () => {
    const demoUsers = toTreeNode(
      node({ identity: { kind: 'TABLE', keyspace: 'demo', table: 'users' }, label: 'users' }),
    );
    const systemUsers = toTreeNode(
      node({
        identity: { kind: 'TABLE', keyspace: 'system_auth', table: 'users' },
        label: 'users',
        system: true,
      }),
    );

    expect(statementForNode(demoUsers, { limit: 100 })).toBe('SELECT * FROM demo.users LIMIT 100;');
    expect(statementForNode(systemUsers, { limit: 100 })).toBe(
      'SELECT * FROM system_auth.users LIMIT 100;',
    );
    expect(systemUsers.system).toBe(true);
  });

  it('falls back to the keyspace name when the server omits the system flag', () => {
    const node1 = toTreeNode(
      node({ identity: { kind: 'KEYSPACE', keyspace: 'system_schema' }, kind: 'KEYSPACE' }),
    );
    expect(node1.system).toBe(true);
  });

  it('qualifies views, types and functions from their own identity fields', () => {
    expect(qualifiedName(toIdentity({ kind: 'VIEW', keyspace: 'demo', view: 'v' }))).toBe('demo.v');
    expect(qualifiedName(toIdentity({ kind: 'TYPE', keyspace: 'demo', name: 'address' }))).toBe(
      'demo.address',
    );
    expect(qualifiedName(toIdentity({ kind: 'KEYSPACE', keyspace: 'demo' }))).toBe('demo');
  });
});
