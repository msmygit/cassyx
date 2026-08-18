/**
 * Maps the contract's `SchemaTree` onto the tree model the browser renders (plan §4).
 *
 * THE invariant, preserved end to end: each node's identity comes from **that node's own**
 * `identity` object in the response. Nothing here reads a parent, an index, or an ambient
 * selection — which is exactly why dragging `demo.users` can never produce a statement against
 * `system_auth.users`.
 */
import type { ApiSchemaIdentity, ApiSchemaNode, ApiSchemaTree } from './schemaTypes';
import { isSystemKeyspace, nodeId, type SchemaIdentity, type SchemaNode } from './model';

/** Contract identity → tree identity. Every field is copied across; nothing is inferred. */
export function toIdentity(identity: ApiSchemaIdentity): SchemaIdentity {
  return {
    keyspace: identity.keyspace,
    ...(identity.table ? { table: identity.table } : {}),
    ...(identity.view ? { view: identity.view } : {}),
    ...(identity.column ? { column: identity.column } : {}),
    ...(identity.index ? { index: identity.index } : {}),
    ...(identity.name ? { name: identity.name } : {}),
    ...(identity.signature ? { signature: identity.signature } : {}),
  };
}

const COLUMN_KINDS: ReadonlySet<string> = new Set([
  'PARTITION_KEY',
  'CLUSTERING',
  'REGULAR',
  'STATIC',
  'COMPACT_VALUE',
]);

/**
 * Column nodes carry `"<cql type> | <column kind>"` as their secondary line, which is enough for
 * the tree to render the type and the PK/CK/static badge without a second request.
 */
function columnMetadata(node: ApiSchemaNode): Partial<SchemaNode> {
  if (node.kind !== 'COLUMN') return {};
  const [rawType = '', rawKind = ''] = (node.detail ?? '').split(' | ');
  const dataType = rawType.trim();
  const kind = rawKind.trim();
  const vector = /^vector<\s*float\s*,\s*(\d+)\s*>$/i.exec(dataType);
  return {
    dataType: dataType || undefined,
    ...(COLUMN_KINDS.has(kind) ? { columnKind: kind as SchemaNode['columnKind'] } : {}),
    vectorDimension: vector?.[1] ? Number(vector[1]) : null,
  };
}

export function toTreeNode(node: ApiSchemaNode): SchemaNode {
  const identity = toIdentity(node.identity);
  const children = node.children?.map(toTreeNode);
  return {
    id: nodeId(node.kind, identity),
    kind: node.kind,
    label: node.label,
    identity,
    system: node.system ?? isSystemKeyspace(identity.keyspace),
    ...(children && children.length > 0 ? { children } : {}),
    ...columnMetadata(node),
  };
}

export function toTree(tree: ApiSchemaTree): SchemaNode[] {
  return tree.keyspaces.map(toTreeNode);
}
