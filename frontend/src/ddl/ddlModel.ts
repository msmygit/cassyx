/**
 * The rules behind every DDL editor (plan §4, §7.1).
 *
 * Two things live here and nowhere else:
 *
 * 1. **Which object types a cluster can actually manage.** Unsupported features are hidden with an
 *    explanation, never rendered broken (plan §7.1) — so the explanation is data, not a comment.
 * 2. **What a "Preview CQL" request looks like** for each object type and action. Every editor
 *    builds one of these; nothing executes without the user having seen its result.
 */
import type {
  CapabilityName,
  DdlAction,
  DdlGenerateRequest,
  DdlObjectType,
} from '../schema/schemaTypes';

export interface ObjectTypeSpec {
  objectType: DdlObjectType;
  label: string;
  /** Plural label for list headings. */
  plural: string;
  /** Capability required to create objects of this type, if any. */
  capability?: CapabilityName;
  /** Shown in the disabled tooltip when {@link capability} is missing. */
  unavailableReason?: string;
  /** Actions the editor offers. */
  actions: DdlAction[];
  /** Object types scoped to a table rather than a keyspace. */
  tableScoped?: boolean;
  /** Object types that are cluster-wide rather than keyspace-scoped. */
  clusterScoped?: boolean;
}

export const OBJECT_TYPES: readonly ObjectTypeSpec[] = [
  {
    objectType: 'KEYSPACE',
    label: 'Keyspace',
    plural: 'Keyspaces',
    actions: ['CREATE', 'ALTER', 'DROP'],
  },
  {
    objectType: 'TABLE',
    label: 'Table',
    plural: 'Tables',
    actions: ['CREATE', 'ALTER', 'DROP', 'TRUNCATE'],
  },
  {
    objectType: 'COLUMN',
    label: 'Column',
    plural: 'Columns',
    actions: ['CREATE', 'ALTER', 'DROP'],
    tableScoped: true,
  },
  {
    objectType: 'INDEX',
    label: 'Index',
    plural: 'Indexes',
    actions: ['CREATE', 'DROP'],
    tableScoped: true,
  },
  {
    objectType: 'MATERIALIZED_VIEW',
    label: 'Materialized view',
    plural: 'Materialized views',
    capability: 'materializedViews',
    unavailableReason:
      'Materialized views are unavailable on this cluster. Astra DB and Amazon Keyspaces do not support them.',
    actions: ['CREATE', 'ALTER', 'DROP'],
  },
  {
    objectType: 'TYPE',
    label: 'User-defined type',
    plural: 'Types',
    actions: ['CREATE', 'ALTER', 'DROP'],
  },
  {
    objectType: 'FUNCTION',
    label: 'Function',
    plural: 'Functions',
    capability: 'udfUda',
    unavailableReason:
      'User-defined functions are unavailable on this cluster. Astra DB does not support them.',
    actions: ['CREATE', 'DROP'],
  },
  {
    objectType: 'AGGREGATE',
    label: 'Aggregate',
    plural: 'Aggregates',
    capability: 'udfUda',
    unavailableReason:
      'User-defined aggregates are unavailable on this cluster. Astra DB does not support them.',
    actions: ['CREATE', 'DROP'],
  },
  {
    objectType: 'ROLE',
    label: 'Role',
    plural: 'Roles',
    capability: 'rolesPermissions',
    unavailableReason:
      'Role management is unavailable on this cluster. Amazon Keyspaces uses IAM instead.',
    actions: ['CREATE', 'ALTER', 'DROP'],
    clusterScoped: true,
  },
  {
    objectType: 'PERMISSION',
    label: 'Permission',
    plural: 'Permissions',
    capability: 'rolesPermissions',
    unavailableReason:
      'Permission management is unavailable on this cluster. Amazon Keyspaces uses IAM instead.',
    actions: ['GRANT', 'REVOKE'],
    clusterScoped: true,
  },
] as const;

export function objectTypeSpec(objectType: DdlObjectType): ObjectTypeSpec {
  const spec = OBJECT_TYPES.find((candidate) => candidate.objectType === objectType);
  if (!spec) throw new Error(`Unknown DDL object type: ${objectType}`);
  return spec;
}

export interface Availability {
  available: boolean;
  /** Present only when unavailable — the tooltip the user sees instead of a broken button. */
  reason?: string;
}

/**
 * Is this object type usable against the connected cluster?
 *
 * An empty/unknown capability set means "we could not fingerprint the cluster", and the honest
 * answer there is to let the user try rather than hide half the product.
 */
export function availability(
  spec: ObjectTypeSpec,
  capabilities: readonly CapabilityName[] | undefined,
): Availability {
  if (!spec.capability) return { available: true };
  if (!capabilities || capabilities.length === 0) return { available: true };
  if (capabilities.includes(spec.capability)) return { available: true };
  return { available: false, reason: spec.unavailableReason };
}

/** Object types offered by the "New object" menu for the current cluster. */
export function availableObjectTypes(
  capabilities: readonly CapabilityName[] | undefined,
): ObjectTypeSpec[] {
  return OBJECT_TYPES.filter((spec) => availability(spec, capabilities).available);
}

/** Vector columns need Cassandra 5.x / Astra, so the type field warns before the server does. */
export function isVectorType(cqlType: string | undefined): boolean {
  return /^\s*vector\s*<\s*float\s*,\s*\d+\s*>\s*$/i.test(cqlType ?? '');
}

export interface DdlTarget {
  keyspace?: string;
  table?: string;
}

/** Builds the `POST /ddl/generate` body. The server owns CQL rendering; the client owns intent. */
export function generateRequest(
  objectType: DdlObjectType,
  action: DdlAction,
  target: DdlTarget,
  definition: Record<string, unknown>,
): DdlGenerateRequest {
  const spec = objectTypeSpec(objectType);
  const request: DdlGenerateRequest = { objectType, action, definition };
  if (!spec.clusterScoped && target.keyspace) request.keyspace = target.keyspace;
  if (spec.tableScoped && target.table) request.table = target.table;
  return request;
}

/**
 * Client-side pre-flight, so an obviously incomplete form does not round-trip.
 *
 * Deliberately thin: the server's generator is the authority and returns per-field problems. This
 * only catches what the user can see is missing.
 */
export function validate(
  objectType: DdlObjectType,
  action: DdlAction,
  definition: Record<string, unknown>,
): string[] {
  const problems: string[] = [];
  const name = typeof definition.name === 'string' ? definition.name.trim() : '';

  if (objectType === 'PERMISSION') {
    if (!asString(definition.role)) problems.push('A role is required.');
    if (!asString(definition.resource)) problems.push('A resource is required.');
    if (!Array.isArray(definition.permissions) || definition.permissions.length === 0) {
      problems.push('Select at least one permission.');
    }
    return problems;
  }

  if (!name) problems.push('A name is required.');

  if (action === 'CREATE') {
    if (objectType === 'TABLE') {
      const columns = Array.isArray(definition.columns) ? definition.columns : [];
      if (columns.length === 0) problems.push('A table needs at least one column.');
      const primaryKey = definition.primaryKey as { partitionKey?: unknown[] } | undefined;
      if (!primaryKey?.partitionKey || primaryKey.partitionKey.length === 0) {
        problems.push('A table needs at least one partition key column.');
      }
    }
    if (objectType === 'TYPE') {
      const fields = Array.isArray(definition.fields) ? definition.fields : [];
      if (fields.length === 0) problems.push('A type needs at least one field.');
    }
    if (objectType === 'INDEX' && !asString(definition.target)) {
      problems.push('An index needs a target column.');
    }
    if (objectType === 'COLUMN' && !asString(definition.type)) {
      problems.push('A column needs a CQL type.');
    }
    if (objectType === 'MATERIALIZED_VIEW' && !asString(definition.baseTable)) {
      problems.push('A materialized view needs a base table.');
    }
  }

  return problems;
}

function asString(value: unknown): string {
  return typeof value === 'string' ? value.trim() : '';
}
