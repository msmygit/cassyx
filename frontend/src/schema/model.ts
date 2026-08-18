/**
 * Schema tree model (plan §4).
 *
 * THE RULE, and the reason this file exists at all:
 * **every node carries its own fully-qualified identity.** Drag, selection and context menus
 * resolve the keyspace/table from `node.identity` — never from the node's position in the tree,
 * never from "the parent I happen to be rendered under", never from a separately-tracked
 * `selectedKeyspace` state variable.
 *
 * The prior-art prototype resolved the keyspace from tree position, so dragging `demo.users`
 * produced `SELECT * FROM system_auth.users LIMIT 100`. `statementForNode` below is the fix, and
 * it is unit-tested against exactly that scenario.
 */
import type { ColumnKind, SchemaNodeKind } from '../api/types';

/** Fully-qualified identity of a CQL object. Present on EVERY node, including keyspace nodes. */
export interface SchemaIdentity {
  keyspace: string;
  /** Table name. Also set on column and index nodes, so a drop never has to guess the base table. */
  table?: string;
  /** Column name, for column nodes. */
  column?: string;
  /** Index name, for index nodes. */
  index?: string;
  /** Materialized view name, for view nodes. */
  view?: string;
  /** Name for kinds that are not table-scoped: TYPE, FUNCTION, AGGREGATE, ROLE. */
  name?: string;
  /** Argument-type list for overloadable objects (UDF/UDA), e.g. `(double,int)`. */
  signature?: string;
}

export interface SchemaNode {
  /** Stable, unique key for React and for selection state. Derived from the identity + kind. */
  id: string;
  kind: SchemaNodeKind;
  /** Display label — the bare name. NEVER use this to build a statement. */
  label: string;
  /** The node's own fully-qualified identity. Authoritative for every action. */
  identity: SchemaIdentity;
  /** True for `system*` keyspaces and everything beneath them. */
  system: boolean;
  children?: SchemaNode[];
  /** Column-only metadata. */
  dataType?: string;
  columnKind?: ColumnKind;
  /** Vector columns get a dimension badge in the tree (plan §6). */
  vectorDimension?: number | null;
}

/**
 * Keyspaces treated as "system" for the show/hide toggle. `system_auth` is deliberately in the
 * list — it is the keyspace the prior-art bug wrongly resolved to.
 */
export const SYSTEM_KEYSPACES: ReadonlySet<string> = new Set([
  'system',
  'system_auth',
  'system_distributed',
  'system_schema',
  'system_traces',
  'system_views',
  'system_virtual_schema',
  'data_endpoint_auth',
  'datastax_sla',
  'dse_system',
  'dse_system_local',
  'dse_security',
  'dse_leases',
  'dse_perf',
  'dse_insights',
  'dse_insights_local',
  'solr_admin',
  'oxsettings',
  'osssettings',
]);

export function isSystemKeyspace(keyspace: string): boolean {
  const lower = keyspace.toLowerCase();
  return SYSTEM_KEYSPACES.has(lower) || lower.startsWith('system_') || lower.startsWith('dse_');
}

/**
 * CQL identifiers are case-insensitive unless double-quoted. An identifier created with mixed case
 * or a reserved word MUST be quoted on every reference or the statement silently targets a
 * different object.
 */
const UNQUOTED_IDENTIFIER = /^[a-z_][a-z0-9_]*$/;

const RESERVED_WORDS: ReadonlySet<string> = new Set([
  'add',
  'allow',
  'alter',
  'and',
  'apply',
  'asc',
  'authorize',
  'batch',
  'begin',
  'by',
  'columnfamily',
  'create',
  'delete',
  'desc',
  'describe',
  'drop',
  'entries',
  'execute',
  'from',
  'full',
  'grant',
  'if',
  'in',
  'index',
  'inet',
  'infinity',
  'insert',
  'into',
  'is',
  'keyspace',
  'limit',
  'materialized',
  'modify',
  'nan',
  'norecursive',
  'not',
  'null',
  'of',
  'on',
  'or',
  'order',
  'primary',
  'rename',
  'replace',
  'revoke',
  'schema',
  'select',
  'set',
  'table',
  'to',
  'token',
  'truncate',
  'unlogged',
  'update',
  'use',
  'using',
  'view',
  'where',
  'with',
]);

/** Quote an identifier only when CQL requires it. */
export function quoteIdentifier(name: string): string {
  if (UNQUOTED_IDENTIFIER.test(name) && !RESERVED_WORDS.has(name)) return name;
  return `"${name.replace(/"/g, '""')}"`;
}

/**
 * The object this identity names, whichever field carries it. Table-scoped kinds use `table`;
 * views use `view`; UDTs, functions, aggregates and roles use `name`.
 */
export function objectName(identity: SchemaIdentity): string | undefined {
  return identity.table ?? identity.view ?? identity.name;
}

/** `keyspace.object`, correctly quoted. Built ONLY from an identity, never from tree position. */
export function qualifiedName(identity: SchemaIdentity): string {
  const keyspace = quoteIdentifier(identity.keyspace);
  const object = objectName(identity);
  if (!object) return keyspace;
  return `${keyspace}.${quoteIdentifier(object)}`;
}

/** Stable node id. Kind is part of it so a table and a UDT of the same name never collide. */
export function nodeId(kind: SchemaNodeKind, identity: SchemaIdentity): string {
  return [
    kind,
    identity.keyspace,
    identity.table ?? identity.view ?? identity.name ?? '',
    identity.column ?? '',
    identity.index ?? '',
    identity.signature ?? '',
  ].join('::');
}

export interface StatementOptions {
  /**
   * Page size hint. Server-side paging via the driver's `PagingState` is the real mechanism
   * (plan §5.1); this only bounds the *first* preview page, and unlike the prior art it is a
   * caller-supplied option rather than a hardcoded `LIMIT 100` dead end.
   */
  limit?: number | null;
}

/**
 * Build the statement for a node — the drag/double-click payload.
 *
 * Regression guard: given a node whose identity is `{keyspace: 'demo', table: 'users'}`, the
 * result must reference `demo.users` regardless of anything else in the tree.
 */
export function statementForNode(node: SchemaNode, options: StatementOptions = {}): string {
  const { identity, kind } = node;
  const limit = options.limit ?? null;
  const limitClause = limit && limit > 0 ? ` LIMIT ${limit}` : '';

  switch (kind) {
    case 'KEYSPACE':
      return `USE ${quoteIdentifier(identity.keyspace)};`;
    case 'TABLE':
    case 'VIEW':
      return `SELECT * FROM ${qualifiedName(identity)}${limitClause};`;
    case 'COLUMN':
      return identity.column ? quoteIdentifier(identity.column) : node.label;
    case 'INDEX':
      return `-- index ${identity.index ?? node.label} on ${qualifiedName(identity)}`;
    case 'TYPE':
      return `-- UDT ${qualifiedName(identity)}`;
    case 'FUNCTION':
    case 'AGGREGATE':
      return `${qualifiedName(identity)}()`;
    default:
      return qualifiedName(identity);
  }
}

/** Payload placed on a drag event. Carries the identity so the drop target never has to guess. */
export interface SchemaDragPayload {
  identity: SchemaIdentity;
  kind: SchemaNodeKind;
  statement: string;
}

export const SCHEMA_DRAG_MIME = 'application/x-cassyx-schema-node';

export function toDragPayload(node: SchemaNode, options?: StatementOptions): SchemaDragPayload {
  return {
    identity: { ...node.identity },
    kind: node.kind,
    statement: statementForNode(node, options),
  };
}

export function parseDragPayload(raw: string): SchemaDragPayload | null {
  try {
    const parsed = JSON.parse(raw) as Partial<SchemaDragPayload>;
    if (!parsed?.identity?.keyspace || !parsed.kind) return null;
    return parsed as SchemaDragPayload;
  } catch {
    return null;
  }
}

/* ------------------------------------------------------------------------ filtering */

export interface FilterOptions {
  search: string;
  showSystem: boolean;
}

function matches(node: SchemaNode, needle: string): boolean {
  if (!needle) return true;
  const lower = needle.toLowerCase();
  return (
    node.label.toLowerCase().includes(lower) ||
    node.identity.keyspace.toLowerCase().includes(lower) ||
    (node.identity.table?.toLowerCase().includes(lower) ?? false)
  );
}

/**
 * Search + "show system keyspaces" filter (both missing from the prior art).
 *
 * A branch is kept when it matches OR when any descendant matches, so searching for a column
 * name still reveals the keyspace/table path leading to it.
 */
export function filterTree(nodes: SchemaNode[], options: FilterOptions): SchemaNode[] {
  const needle = options.search.trim();
  const result: SchemaNode[] = [];

  for (const node of nodes) {
    if (node.system && !options.showSystem) continue;
    const children = node.children ? filterTree(node.children, options) : undefined;
    const selfMatches = matches(node, needle);
    if (selfMatches || (children && children.length > 0)) {
      result.push({
        ...node,
        // When the node itself matched, keep its full (system-filtered) subtree.
        children:
          selfMatches && node.children
            ? filterTree(node.children, { ...options, search: '' })
            : children,
      });
    }
  }
  return result;
}

/** Ids of every branch in a (filtered) tree — used to auto-expand search results. */
export function collectExpandableIds(nodes: SchemaNode[], acc: string[] = []): string[] {
  for (const node of nodes) {
    if (node.children && node.children.length > 0) {
      acc.push(node.id);
      collectExpandableIds(node.children, acc);
    }
  }
  return acc;
}

/** Depth-first lookup by id. */
export function findNode(nodes: SchemaNode[], id: string): SchemaNode | null {
  for (const node of nodes) {
    if (node.id === id) return node;
    const found = node.children ? findNode(node.children, id) : null;
    if (found) return found;
  }
  return null;
}
