/**
 * The visual editor for each object type, described as data (plan §4).
 *
 * Ten hand-written forms would drift from each other and from the contract; one declarative field
 * list per object type keeps the editors, the validation in `ddlModel` and the request bodies in
 * `schemaApi` describing the same thing.
 */
import type { DdlAction, DdlObjectType } from '../schema/schemaTypes';

export type FieldKind =
  | 'text'
  | 'multiline'
  | 'number'
  | 'boolean'
  | 'select'
  | 'tags'
  | 'columns'
  | 'typeFields'
  | 'functionArgs'
  | 'primaryKey'
  | 'replication'
  | 'permissions';

export interface FieldSpec {
  key: string;
  label: string;
  kind: FieldKind;
  help?: string;
  placeholder?: string;
  options?: readonly string[];
  /** Only rendered for these actions; omitted means "every action this editor offers". */
  actions?: DdlAction[];
}

const IF_NOT_EXISTS: FieldSpec = {
  key: 'ifNotExists',
  label: 'IF NOT EXISTS',
  kind: 'boolean',
  help: 'Emit IF NOT EXISTS so re-running the statement is not an error.',
  actions: ['CREATE'],
};

const NAME: FieldSpec = { key: 'name', label: 'Name', kind: 'text' };

export const EDITOR_FIELDS: Record<DdlObjectType, readonly FieldSpec[]> = {
  KEYSPACE: [
    NAME,
    { key: 'replication', label: 'Replication', kind: 'replication' },
    { key: 'durableWrites', label: 'Durable writes', kind: 'boolean' },
    IF_NOT_EXISTS,
  ],
  TABLE: [
    NAME,
    { key: 'columns', label: 'Columns', kind: 'columns', actions: ['CREATE'] },
    { key: 'primaryKey', label: 'Primary key', kind: 'primaryKey', actions: ['CREATE'] },
    {
      key: 'comment',
      label: 'Comment',
      kind: 'multiline',
      help: 'Stored as the table WITH comment option.',
    },
    {
      key: 'compactionClass',
      label: 'Compaction strategy',
      kind: 'select',
      options: [
        '',
        'SizeTieredCompactionStrategy',
        'LeveledCompactionStrategy',
        'TimeWindowCompactionStrategy',
        'UnifiedCompactionStrategy',
      ],
    },
    { key: 'gcGraceSeconds', label: 'gc_grace_seconds', kind: 'number' },
    { key: 'defaultTimeToLive', label: 'default_time_to_live', kind: 'number' },
    { key: 'bloomFilterFpChance', label: 'bloom_filter_fp_chance', kind: 'number' },
    { key: 'speculativeRetry', label: 'speculative_retry', kind: 'text', placeholder: '99p' },
    IF_NOT_EXISTS,
  ],
  COLUMN: [
    NAME,
    {
      key: 'type',
      label: 'CQL type',
      kind: 'text',
      placeholder: 'text, list<int>, frozen<address>, vector<float, 1536>',
      actions: ['CREATE'],
    },
    { key: 'static', label: 'Static column', kind: 'boolean', actions: ['CREATE'] },
    { key: 'newName', label: 'New name', kind: 'text', actions: ['ALTER'] },
    { key: 'newType', label: 'New CQL type', kind: 'text', actions: ['ALTER'] },
  ],
  INDEX: [
    NAME,
    {
      key: 'target',
      label: 'Target',
      kind: 'text',
      placeholder: 'email, or keys(preferences) / values(tags) / full(coords)',
      actions: ['CREATE'],
    },
    {
      key: 'kind',
      label: 'Index kind',
      kind: 'select',
      options: ['SAI', 'COMPOSITES', 'KEYS', 'CUSTOM', 'DSE_SEARCH'],
      actions: ['CREATE'],
    },
    {
      key: 'className',
      label: 'Custom index class',
      kind: 'text',
      help: 'Required for CUSTOM; defaulted for SAI and DSE Search.',
      actions: ['CREATE'],
    },
    IF_NOT_EXISTS,
  ],
  MATERIALIZED_VIEW: [
    NAME,
    { key: 'baseTable', label: 'Base table', kind: 'text', actions: ['CREATE'] },
    {
      key: 'selectedColumns',
      label: 'Selected columns',
      kind: 'tags',
      help: 'Leave empty for SELECT *.',
      actions: ['CREATE'],
    },
    { key: 'primaryKey', label: 'Primary key', kind: 'primaryKey', actions: ['CREATE'] },
    {
      key: 'whereClause',
      label: 'WHERE clause',
      kind: 'multiline',
      help: 'Generated as "<col> IS NOT NULL" for every primary-key column when left empty.',
      actions: ['CREATE'],
    },
    { key: 'comment', label: 'Comment', kind: 'multiline', actions: ['ALTER'] },
    IF_NOT_EXISTS,
  ],
  TYPE: [
    NAME,
    { key: 'fields', label: 'Fields', kind: 'typeFields', actions: ['CREATE'] },
    {
      key: 'addFields',
      label: 'Add fields',
      kind: 'typeFields',
      help: 'CQL can add and rename UDT fields; it cannot drop them.',
      actions: ['ALTER'],
    },
    IF_NOT_EXISTS,
  ],
  FUNCTION: [
    NAME,
    { key: 'arguments', label: 'Arguments', kind: 'functionArgs', actions: ['CREATE'] },
    { key: 'returnType', label: 'Returns', kind: 'text', actions: ['CREATE'] },
    {
      key: 'language',
      label: 'Language',
      kind: 'select',
      options: ['java', 'javascript'],
      actions: ['CREATE'],
    },
    { key: 'body', label: 'Body', kind: 'multiline', actions: ['CREATE'] },
    {
      key: 'nullHandling',
      label: 'Null handling',
      kind: 'select',
      options: ['CALLED_ON_NULL_INPUT', 'RETURNS_NULL_ON_NULL_INPUT'],
      actions: ['CREATE'],
    },
    { key: 'orReplace', label: 'OR REPLACE', kind: 'boolean', actions: ['CREATE'] },
    {
      key: 'signature',
      label: 'Signature',
      kind: 'text',
      placeholder: 'avg_state(double,int)',
      actions: ['DROP'],
    },
    IF_NOT_EXISTS,
  ],
  AGGREGATE: [
    NAME,
    { key: 'argumentTypes', label: 'Argument types', kind: 'tags', actions: ['CREATE'] },
    { key: 'stateFunction', label: 'SFUNC', kind: 'text', actions: ['CREATE'] },
    { key: 'stateType', label: 'STYPE', kind: 'text', actions: ['CREATE'] },
    { key: 'finalFunction', label: 'FINALFUNC', kind: 'text', actions: ['CREATE'] },
    { key: 'initCondition', label: 'INITCOND', kind: 'text', actions: ['CREATE'] },
    { key: 'orReplace', label: 'OR REPLACE', kind: 'boolean', actions: ['CREATE'] },
    {
      key: 'signature',
      label: 'Signature',
      kind: 'text',
      placeholder: 'average(double)',
      actions: ['DROP'],
    },
    IF_NOT_EXISTS,
  ],
  ROLE: [
    NAME,
    {
      key: 'password',
      label: 'Password',
      kind: 'text',
      help: 'Write-only. It appears in the preview so you can review the statement, and is redacted from the result.',
    },
    { key: 'login', label: 'LOGIN', kind: 'boolean' },
    { key: 'superuser', label: 'SUPERUSER', kind: 'boolean' },
    { key: 'memberOf', label: 'Member of', kind: 'tags' },
    IF_NOT_EXISTS,
  ],
  PERMISSION: [
    { key: 'role', label: 'Role', kind: 'text' },
    {
      key: 'resource',
      label: 'Resource',
      kind: 'text',
      placeholder: 'keyspace demo, or table demo.users',
    },
    { key: 'permissions', label: 'Permissions', kind: 'permissions' },
  ],
};

export const CQL_PERMISSIONS = [
  'ALL',
  'CREATE',
  'ALTER',
  'DROP',
  'SELECT',
  'MODIFY',
  'AUTHORIZE',
  'DESCRIBE',
  'EXECUTE',
] as const;

export function fieldsFor(objectType: DdlObjectType, action: DdlAction): FieldSpec[] {
  return EDITOR_FIELDS[objectType].filter(
    (field) => !field.actions || field.actions.includes(action),
  );
}

/**
 * Turns the flat form state into the `definition` payload the contract expects.
 *
 * The only real work is the table/view options: the form is flat because a nested options tree is
 * miserable to fill in, while the contract's `TableOptions` is nested because that is what CQL is.
 */
export function toDefinition(
  objectType: DdlObjectType,
  action: DdlAction,
  form: Record<string, unknown>,
): Record<string, unknown> {
  const definition: Record<string, unknown> = {};
  for (const field of fieldsFor(objectType, action)) {
    const value = form[field.key];
    if (value === undefined || value === '' || value === null) continue;
    if (Array.isArray(value) && value.length === 0) continue;
    definition[field.key] = value;
  }

  if (objectType === 'TABLE' || objectType === 'MATERIALIZED_VIEW') {
    const options: Record<string, unknown> = {};
    for (const key of [
      'comment',
      'gcGraceSeconds',
      'defaultTimeToLive',
      'bloomFilterFpChance',
      'speculativeRetry',
    ]) {
      if (definition[key] !== undefined) {
        options[key] = definition[key];
        delete definition[key];
      }
    }
    if (definition.compactionClass) {
      options.compaction = { class: definition.compactionClass };
      delete definition.compactionClass;
    }
    if (Object.keys(options).length > 0) {
      if (action === 'ALTER') {
        // ALTER TABLE takes a bare TableOptions body, not a wrapper.
        return { name: definition.name, ...options };
      }
      definition.options = options;
    }
  }

  return definition;
}
