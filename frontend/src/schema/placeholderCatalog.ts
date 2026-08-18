/**
 * SHELL PLACEHOLDER — replaced by Phase 1 workstream B (§4), which reads the real catalog from
 * `session.getMetadata()` via `GET /api/connections/{id}/schema`.
 *
 * It exists so the app shell has a realistic tree to lay out and test against. The fixture
 * deliberately includes `demo.users` AND `system_auth.users` — same table name, different
 * keyspaces — because that collision is exactly what broke the prior-art tool.
 */
import type { SchemaNodeKind } from '../api/types';
import { isSystemKeyspace, nodeId, type SchemaIdentity, type SchemaNode } from './model';

interface ColumnSpec {
  name: string;
  dataType: string;
  columnKind: SchemaNode['columnKind'];
  vectorDimension?: number;
}

interface TableSpec {
  name: string;
  kind?: Extract<SchemaNodeKind, 'TABLE' | 'VIEW'>;
  columns: ColumnSpec[];
}

function makeNode(
  kind: SchemaNodeKind,
  label: string,
  identity: SchemaIdentity,
  extra: Partial<SchemaNode> = {},
): SchemaNode {
  return {
    id: nodeId(kind, identity),
    kind,
    label,
    identity,
    system: isSystemKeyspace(identity.keyspace),
    ...extra,
  };
}

function buildTable(keyspace: string, spec: TableSpec): SchemaNode {
  const identity: SchemaIdentity = { keyspace, table: spec.name };
  return makeNode(spec.kind ?? 'TABLE', spec.name, identity, {
    children: spec.columns.map((column) =>
      makeNode(
        'COLUMN',
        column.name,
        { keyspace, table: spec.name, column: column.name },
        {
          dataType: column.dataType,
          columnKind: column.columnKind,
          vectorDimension: column.vectorDimension ?? null,
        },
      ),
    ),
  });
}

function buildKeyspace(keyspace: string, tables: TableSpec[]): SchemaNode {
  return makeNode(
    'KEYSPACE',
    keyspace,
    { keyspace },
    {
      children: tables.map((table) => buildTable(keyspace, table)),
    },
  );
}

export function placeholderCatalog(): SchemaNode[] {
  return [
    buildKeyspace('demo', [
      {
        name: 'users',
        columns: [
          { name: 'user_id', dataType: 'uuid', columnKind: 'PARTITION_KEY' },
          { name: 'created_at', dataType: 'timestamp', columnKind: 'CLUSTERING' },
          { name: 'email', dataType: 'text', columnKind: 'REGULAR' },
          { name: 'preferences', dataType: 'map<text, text>', columnKind: 'REGULAR' },
        ],
      },
      {
        name: 'orders',
        columns: [
          { name: 'order_id', dataType: 'uuid', columnKind: 'PARTITION_KEY' },
          { name: 'user_id', dataType: 'uuid', columnKind: 'REGULAR' },
          { name: 'total_cents', dataType: 'bigint', columnKind: 'REGULAR' },
        ],
      },
      {
        name: 'orders_by_user',
        kind: 'VIEW',
        columns: [
          { name: 'user_id', dataType: 'uuid', columnKind: 'PARTITION_KEY' },
          { name: 'order_id', dataType: 'uuid', columnKind: 'CLUSTERING' },
        ],
      },
      {
        name: 'product_embeddings',
        columns: [
          { name: 'product_id', dataType: 'uuid', columnKind: 'PARTITION_KEY' },
          { name: 'title', dataType: 'text', columnKind: 'REGULAR' },
          {
            name: 'embedding',
            dataType: 'vector<float, 1536>',
            columnKind: 'REGULAR',
            vectorDimension: 1536,
          },
        ],
      },
    ]),
    buildKeyspace('analytics', [
      {
        name: 'events_by_day',
        columns: [
          { name: 'day', dataType: 'date', columnKind: 'PARTITION_KEY' },
          { name: 'event_id', dataType: 'timeuuid', columnKind: 'CLUSTERING' },
          { name: 'payload', dataType: 'blob', columnKind: 'REGULAR' },
        ],
      },
    ]),
    // Same table name as demo.users — the prior-art keyspace-resolution bug in fixture form.
    buildKeyspace('system_auth', [
      {
        name: 'users',
        columns: [{ name: 'name', dataType: 'text', columnKind: 'PARTITION_KEY' }],
      },
      {
        name: 'roles',
        columns: [{ name: 'role', dataType: 'text', columnKind: 'PARTITION_KEY' }],
      },
    ]),
    buildKeyspace('system_schema', [
      {
        name: 'tables',
        columns: [{ name: 'keyspace_name', dataType: 'text', columnKind: 'PARTITION_KEY' }],
      },
    ]),
  ];
}
