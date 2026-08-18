import { describe, expect, it } from 'vitest';
import {
  availability,
  availableObjectTypes,
  generateRequest,
  isVectorType,
  objectTypeSpec,
  OBJECT_TYPES,
  validate,
} from './ddlModel';
import { fieldsFor, toDefinition } from './editorFields';

describe('ddlModel capability gating (plan §7.1)', () => {
  it('hides materialized views on Astra with an explanation, never a broken button', () => {
    const spec = objectTypeSpec('MATERIALIZED_VIEW');
    const gate = availability(spec, ['sai', 'vector']);

    expect(gate.available).toBe(false);
    expect(gate.reason).toMatch(/Astra/);
  });

  it('hides UDF/UDA on Astra and roles on Amazon Keyspaces', () => {
    expect(availability(objectTypeSpec('FUNCTION'), ['sai']).available).toBe(false);
    expect(availability(objectTypeSpec('AGGREGATE'), ['sai']).available).toBe(false);
    expect(availability(objectTypeSpec('ROLE'), ['truncate']).available).toBe(false);
    expect(availability(objectTypeSpec('PERMISSION'), ['truncate']).available).toBe(false);
  });

  it('allows everything when the cluster could not be fingerprinted', () => {
    expect(availability(objectTypeSpec('MATERIALIZED_VIEW'), []).available).toBe(true);
    expect(availability(objectTypeSpec('MATERIALIZED_VIEW'), undefined).available).toBe(true);
  });

  it('always allows the capability-free object types', () => {
    for (const objectType of ['KEYSPACE', 'TABLE', 'COLUMN', 'INDEX', 'TYPE'] as const) {
      expect(availability(objectTypeSpec(objectType), ['sai']).available).toBe(true);
    }
  });

  it('filters the "new object" menu by capability', () => {
    const onAstra = availableObjectTypes(['sai', 'vector']).map((spec) => spec.objectType);

    expect(onAstra).toContain('TABLE');
    expect(onAstra).not.toContain('MATERIALIZED_VIEW');
    expect(onAstra).not.toContain('FUNCTION');
    expect(availableObjectTypes(undefined)).toHaveLength(OBJECT_TYPES.length);
  });

  it('rejects an unknown object type loudly', () => {
    // @ts-expect-error deliberately outside the contract enum
    expect(() => objectTypeSpec('NONSENSE')).toThrow(/Unknown DDL object type/);
  });
});

describe('generateRequest', () => {
  it('scopes keyspace-level objects to their keyspace', () => {
    expect(generateRequest('TABLE', 'CREATE', { keyspace: 'demo' }, { name: 'users' })).toEqual({
      objectType: 'TABLE',
      action: 'CREATE',
      keyspace: 'demo',
      definition: { name: 'users' },
    });
  });

  it('adds the table only for table-scoped object types', () => {
    expect(
      generateRequest('INDEX', 'CREATE', { keyspace: 'demo', table: 'users' }, { name: 'i' }),
    ).toMatchObject({ keyspace: 'demo', table: 'users' });
    expect(
      generateRequest('TYPE', 'CREATE', { keyspace: 'demo', table: 'users' }, { name: 't' }),
    ).not.toHaveProperty('table');
  });

  it('omits the keyspace for cluster-scoped object types', () => {
    const request = generateRequest('ROLE', 'CREATE', { keyspace: 'demo' }, { name: 'r' });
    expect(request).not.toHaveProperty('keyspace');
  });
});

describe('validate', () => {
  it('requires a name for everything but permissions', () => {
    expect(validate('TABLE', 'DROP', {})).toContain('A name is required.');
    expect(validate('PERMISSION', 'GRANT', {})).not.toContain('A name is required.');
  });

  it('demands columns and a partition key before generating a CREATE TABLE', () => {
    expect(validate('TABLE', 'CREATE', { name: 'users' })).toEqual([
      'A table needs at least one column.',
      'A table needs at least one partition key column.',
    ]);
    expect(
      validate('TABLE', 'CREATE', {
        name: 'users',
        columns: [{ name: 'id', type: 'uuid' }],
        primaryKey: { partitionKey: ['id'] },
      }),
    ).toEqual([]);
  });

  it('checks the per-object-type essentials', () => {
    expect(validate('TYPE', 'CREATE', { name: 'address' })).toContain(
      'A type needs at least one field.',
    );
    expect(validate('INDEX', 'CREATE', { name: 'i' })).toContain('An index needs a target column.');
    expect(validate('COLUMN', 'CREATE', { name: 'c' })).toContain('A column needs a CQL type.');
    expect(validate('MATERIALIZED_VIEW', 'CREATE', { name: 'v' })).toContain(
      'A materialized view needs a base table.',
    );
  });

  it('validates permission grants on their own terms', () => {
    expect(validate('PERMISSION', 'GRANT', {})).toEqual([
      'A role is required.',
      'A resource is required.',
      'Select at least one permission.',
    ]);
    expect(
      validate('PERMISSION', 'GRANT', {
        role: 'app_reader',
        resource: 'table demo.users',
        permissions: ['SELECT'],
      }),
    ).toEqual([]);
  });

  it('skips CREATE-only checks on other actions', () => {
    expect(validate('TABLE', 'DROP', { name: 'users' })).toEqual([]);
  });
});

describe('isVectorType', () => {
  it('recognises vector columns so the form can warn before the server does', () => {
    expect(isVectorType('vector<float, 1536>')).toBe(true);
    expect(isVectorType('VECTOR<FLOAT,3>')).toBe(true);
    expect(isVectorType('list<float>')).toBe(false);
    expect(isVectorType(undefined)).toBe(false);
  });
});

describe('editorFields', () => {
  it('offers action-appropriate fields only', () => {
    const createKeys = fieldsFor('COLUMN', 'CREATE').map((field) => field.key);
    const alterKeys = fieldsFor('COLUMN', 'ALTER').map((field) => field.key);

    expect(createKeys).toEqual(['name', 'type', 'static']);
    expect(alterKeys).toEqual(['name', 'newName', 'newType']);
  });

  it('drops empty values from the definition payload', () => {
    expect(
      toDefinition('COLUMN', 'CREATE', { name: 'email', type: 'text', static: false, junk: 'x' }),
    ).toEqual({ name: 'email', type: 'text', static: false });
    expect(toDefinition('COLUMN', 'CREATE', { name: '', type: 'text' })).toEqual({ type: 'text' });
  });

  it('nests table options the way the contract models them', () => {
    const definition = toDefinition('TABLE', 'CREATE', {
      name: 'users',
      columns: [{ name: 'id', type: 'uuid' }],
      primaryKey: { partitionKey: ['id'] },
      comment: 'Application users',
      gcGraceSeconds: 864000,
      compactionClass: 'LeveledCompactionStrategy',
    });

    expect(definition.options).toEqual({
      comment: 'Application users',
      gcGraceSeconds: 864000,
      compaction: { class: 'LeveledCompactionStrategy' },
    });
    expect(definition).not.toHaveProperty('comment');
  });

  it('flattens options for ALTER, which takes a bare TableOptions body', () => {
    expect(toDefinition('TABLE', 'ALTER', { name: 'users', comment: 'hi' })).toEqual({
      name: 'users',
      comment: 'hi',
    });
  });

  it('leaves definitions without options untouched', () => {
    expect(toDefinition('TABLE', 'DROP', { name: 'users' })).toEqual({ name: 'users' });
  });
});
