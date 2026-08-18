import { describe, expect, it, vi } from 'vitest';
import { ApiClient } from '../api/client';
import * as api from './schemaApi';

interface Call {
  url: string;
  method: string;
  body: unknown;
}

function recordingClient(): { client: ApiClient; calls: Call[] } {
  const calls: Call[] = [];
  const fetchImpl = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    calls.push({
      url: String(input),
      method: init?.method ?? 'GET',
      body: init?.body ? JSON.parse(String(init.body)) : undefined,
    });
    return new Response(JSON.stringify({ ok: true }), {
      status: 200,
      headers: { 'content-type': 'application/json' },
    });
  });
  return { client: new ApiClient({ baseUrl: '', fetchImpl }), calls };
}

const C = 'conn-1';

describe('schemaApi', () => {
  it('builds tree and search URLs with their query parameters', async () => {
    const { client, calls } = recordingClient();

    await api.getSchemaTree(C, true, client);
    await api.searchSchema(C, 'user', { kinds: ['TABLE', 'COLUMN'], limit: 25 }, client);

    expect(calls[0]?.url).toBe('/api/connections/conn-1/schema/tree?includeSystem=true');
    expect(calls[1]?.url).toBe(
      '/api/connections/conn-1/schema/search?q=user&kinds=TABLE%2CCOLUMN&includeSystem=false&limit=25',
    );
  });

  it('omits the kinds filter when none are selected', async () => {
    const { client, calls } = recordingClient();
    await api.searchSchema(C, 'user', {}, client);
    expect(calls[0]?.url).not.toContain('kinds=');
  });

  it('covers the keyspace lifecycle', async () => {
    const { client, calls } = recordingClient();

    await api.listKeyspaces(C, false, client);
    await api.createKeyspace(
      C,
      { name: 'demo', replication: { strategy: 'SimpleStrategy' } },
      client,
    );
    await api.getKeyspace(C, 'demo', client);
    await api.alterKeyspace(
      C,
      'demo',
      { name: 'demo', replication: { strategy: 'SimpleStrategy' } },
      client,
    );
    await api.dropKeyspace(C, 'demo', false, client);

    expect(calls.map((call) => `${call.method} ${call.url}`)).toEqual([
      'GET /api/connections/conn-1/keyspaces?includeSystem=false',
      'POST /api/connections/conn-1/keyspaces',
      'GET /api/connections/conn-1/keyspaces/demo',
      'PUT /api/connections/conn-1/keyspaces/demo',
      'DELETE /api/connections/conn-1/keyspaces/demo?ifExists=false',
    ]);
  });

  it('covers the table lifecycle including info, comment and statistics', async () => {
    const { client, calls } = recordingClient();

    await api.listTables(C, 'demo', client);
    await api.createTable(
      C,
      'demo',
      {
        name: 'users',
        columns: [{ name: 'id', type: 'uuid' }],
        primaryKey: { partitionKey: ['id'] },
      },
      client,
    );
    await api.getTable(C, 'demo', 'users', client);
    await api.alterTable(C, 'demo', 'users', { comment: 'hi' }, client);
    await api.truncateTable(C, 'demo', 'users', client);
    await api.getTableInfo(C, 'demo', 'users', client);
    await api.updateTableComment(C, 'demo', 'users', { comment: 'hi' }, client);
    await api.getTableStatistics(C, 'demo', 'users', client);
    await api.dropTable(C, 'demo', 'users', true, client);

    expect(calls.map((call) => `${call.method} ${call.url}`)).toEqual([
      'GET /api/connections/conn-1/keyspaces/demo/tables',
      'POST /api/connections/conn-1/keyspaces/demo/tables',
      'GET /api/connections/conn-1/keyspaces/demo/tables/users',
      'PUT /api/connections/conn-1/keyspaces/demo/tables/users',
      'POST /api/connections/conn-1/keyspaces/demo/tables/users/truncate',
      'GET /api/connections/conn-1/keyspaces/demo/tables/users/info',
      'PUT /api/connections/conn-1/keyspaces/demo/tables/users/comment',
      'GET /api/connections/conn-1/keyspaces/demo/tables/users/statistics',
      'DELETE /api/connections/conn-1/keyspaces/demo/tables/users?ifExists=true',
    ]);
  });

  it('covers columns and indexes', async () => {
    const { client, calls } = recordingClient();

    await api.listColumns(C, 'demo', 'users', client);
    await api.addColumn(C, 'demo', 'users', { name: 'email', type: 'text' }, client);
    await api.alterColumn(C, 'demo', 'users', 'email', { newName: 'user_email' }, client);
    await api.dropColumn(C, 'demo', 'users', 'email', client);
    await api.listIndexes(C, 'demo', 'users', client);
    await api.createIndex(C, 'demo', 'users', { name: 'i', target: 'email', kind: 'SAI' }, client);
    await api.dropIndex(C, 'demo', 'users', 'i', true, client);

    expect(calls.map((call) => `${call.method} ${call.url}`)).toEqual([
      'GET /api/connections/conn-1/keyspaces/demo/tables/users/columns',
      'POST /api/connections/conn-1/keyspaces/demo/tables/users/columns',
      'PATCH /api/connections/conn-1/keyspaces/demo/tables/users/columns/email',
      'DELETE /api/connections/conn-1/keyspaces/demo/tables/users/columns/email',
      'GET /api/connections/conn-1/keyspaces/demo/tables/users/indexes',
      'POST /api/connections/conn-1/keyspaces/demo/tables/users/indexes',
      'DELETE /api/connections/conn-1/keyspaces/demo/tables/users/indexes/i?ifExists=true',
    ]);
  });

  it('covers views, types, functions and aggregates', async () => {
    const { client, calls } = recordingClient();

    await api.listMaterializedViews(C, 'demo', client);
    await api.createMaterializedView(
      C,
      'demo',
      { name: 'v', baseTable: 'users', primaryKey: { partitionKey: ['email'] } },
      client,
    );
    await api.getMaterializedView(C, 'demo', 'v', client);
    await api.alterMaterializedView(C, 'demo', 'v', { comment: 'hi' }, client);
    await api.dropMaterializedView(C, 'demo', 'v', true, client);

    await api.listUserDefinedTypes(C, 'demo', client);
    await api.createUserDefinedType(C, 'demo', { name: 'address', fields: [] }, client);
    await api.getUserDefinedType(C, 'demo', 'address', client);
    await api.alterUserDefinedType(C, 'demo', 'address', { addFields: [] }, client);
    await api.dropUserDefinedType(C, 'demo', 'address', true, client);

    await api.listFunctions(C, 'demo', client);
    await api.createFunction(
      C,
      'demo',
      { name: 'f', arguments: [], returnType: 'int', language: 'java', body: 'return 1;' },
      client,
    );
    await api.getFunction(C, 'demo', 'avg_state(double,int)', client);
    await api.dropFunction(C, 'demo', 'avg_state(double,int)', true, client);

    await api.listAggregates(C, 'demo', client);
    await api.createAggregate(
      C,
      'demo',
      { name: 'a', argumentTypes: ['double'], stateFunction: 'f', stateType: 'double' },
      client,
    );
    await api.getAggregate(C, 'demo', 'average(double)', client);
    await api.dropAggregate(C, 'demo', 'average(double)', true, client);

    // Overloadable signatures must be URL-encoded, not pasted raw into the path.
    expect(calls.map((call) => call.url)).toContain(
      '/api/connections/conn-1/keyspaces/demo/functions/avg_state(double%2Cint)',
    );
    expect(calls).toHaveLength(18);
  });

  it('covers roles and permissions', async () => {
    const { client, calls } = recordingClient();

    await api.listRoles(C, client);
    await api.createRole(C, { name: 'app_reader' }, client);
    await api.alterRole(C, 'app_reader', { name: 'app_reader', superuser: true }, client);
    await api.dropRole(C, 'app_reader', true, client);
    await api.listPermissions(C, { role: 'app_reader', resource: 'table demo.users' }, client);
    await api.grantPermission(
      C,
      { role: 'app_reader', resource: 'table demo.users', permissions: ['SELECT'] },
      client,
    );
    await api.revokePermission(
      C,
      { role: 'app_reader', resource: 'table demo.users', permissions: ['SELECT'] },
      client,
    );

    expect(calls[4]?.url).toBe(
      '/api/connections/conn-1/permissions?role=app_reader&resource=table+demo.users',
    );
    expect(calls[5]?.body).toMatchObject({ permissions: ['SELECT'] });
    expect(calls.map((call) => call.method)).toEqual([
      'GET',
      'POST',
      'PUT',
      'DELETE',
      'GET',
      'POST',
      'POST',
    ]);
  });

  it('covers generate, preview and execute', async () => {
    const { client, calls } = recordingClient();

    await api.generateDdl(C, { objectType: 'TABLE', action: 'CREATE', definition: {} }, client);
    await api.previewDdl(
      C,
      { identity: { kind: 'TABLE', keyspace: 'demo', table: 'users' } },
      client,
    );
    await api.executeDdl(C, { cql: 'DROP TABLE demo.users;' }, client);

    expect(calls.map((call) => call.url)).toEqual([
      '/api/connections/conn-1/ddl/generate',
      '/api/connections/conn-1/ddl/preview',
      '/api/connections/conn-1/ddl/execute',
    ]);
  });

  it('encodes identifiers that need it', async () => {
    const { client, calls } = recordingClient();
    await api.getTable(C, 'my keyspace', 'User Events', client);
    expect(calls[0]?.url).toBe(
      '/api/connections/conn-1/keyspaces/my%20keyspace/tables/User%20Events',
    );
  });
});
