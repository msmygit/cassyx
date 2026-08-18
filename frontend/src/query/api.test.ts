import { describe, expect, it, vi } from 'vitest';
import { ApiClient } from '../api/client';
import {
  QUERY_ID_HEADER,
  cancelQuery,
  checkRowEditability,
  closeResultSet,
  createSavedScript,
  deleteRow,
  deleteSavedScript,
  executeBatch,
  executeQuery,
  fetchNextPage,
  fetchPreviousPage,
  generateRowStatements,
  getQueryTrace,
  getResultSetState,
  insertRow,
  lexCqlScript,
  listQueryHistory,
  listSavedScripts,
  splitCqlScript,
  updateRow,
  updateSavedScript,
  clearQueryHistory,
} from './api';

interface Call {
  url: string;
  init: RequestInit;
}

function stubClient(body: unknown = {}) {
  const calls: Call[] = [];
  const fetchImpl = vi.fn(async (url: string | URL | Request, init?: RequestInit) => {
    calls.push({ url: String(url), init: init ?? {} });
    return new Response(JSON.stringify(body), {
      status: 200,
      headers: { 'content-type': 'application/json' },
    });
  }) as unknown as typeof fetch;
  return { client: new ApiClient({ fetchImpl }), calls };
}

describe('executeQuery', () => {
  it('sends the client-generated query id so the UI can cancel mid-flight', async () => {
    const { client, calls } = stubClient({ resultHandle: 'rs_1' });

    await executeQuery('conn-1', { cql: 'SELECT 1' }, { queryId: 'q-1' }, client);

    expect(calls[0]?.url).toBe('/api/connections/conn-1/query');
    const headers = calls[0]?.init.headers as Record<string, string>;
    expect(headers[QUERY_ID_HEADER]).toBe('q-1');
    expect(JSON.parse(String(calls[0]?.init.body))).toEqual({ cql: 'SELECT 1' });
  });

  it('omits the header when no id is supplied', async () => {
    const { client, calls } = stubClient();

    await executeQuery('conn-1', { cql: 'SELECT 1' }, {}, client);

    const headers = calls[0]?.init.headers as Record<string, string>;
    expect(headers[QUERY_ID_HEADER]).toBeUndefined();
  });

  it('URL-encodes path segments', async () => {
    const { client, calls } = stubClient();

    await executeQuery('a/b', { cql: 'SELECT 1' }, {}, client);

    expect(calls[0]?.url).toBe('/api/connections/a%2Fb/query');
  });
});

describe('paging', () => {
  it('posts the opaque token to the next-page endpoint', async () => {
    const { client, calls } = stubClient();

    await fetchNextPage('rs_1', 'tok', 250, client);

    expect(calls[0]?.url).toBe('/api/query/results/rs_1/next-page');
    expect(JSON.parse(String(calls[0]?.init.body))).toEqual({ pageToken: 'tok', fetchSize: 250 });
  });

  it('replays a retained token for the previous page', async () => {
    const { client, calls } = stubClient();

    await fetchPreviousPage('rs_1', 'tok', undefined, client);

    expect(calls[0]?.url).toBe('/api/query/results/rs_1/previous-page');
    expect(JSON.parse(String(calls[0]?.init.body))).toEqual({ pageToken: 'tok' });
  });

  it('reads and releases the result handle', async () => {
    const { client, calls } = stubClient();

    await getResultSetState('rs_1', client);
    await closeResultSet('rs_1', client);

    expect(calls[0]?.init.method).toBe('GET');
    expect(calls[1]?.init.method).toBe('DELETE');
  });
});

describe('execution control', () => {
  it('cancels and fetches the trace by query id', async () => {
    const { client, calls } = stubClient();

    await cancelQuery('q-1', client);
    await getQueryTrace('q-1', client);

    expect(calls[0]?.url).toBe('/api/query/executions/q-1/cancel');
    expect(calls[1]?.url).toBe('/api/query/executions/q-1/trace');
  });
});

describe('script services', () => {
  it('splits with the cursor offset when one is given', async () => {
    const { client, calls } = stubClient({ statements: [] });

    await splitCqlScript('SELECT 1;', 4, client);
    await splitCqlScript('SELECT 1;', undefined, client);
    await lexCqlScript('SELECT 1;', client);

    expect(JSON.parse(String(calls[0]?.init.body))).toEqual({ cql: 'SELECT 1;', cursorOffset: 4 });
    expect(JSON.parse(String(calls[1]?.init.body))).toEqual({ cql: 'SELECT 1;' });
    expect(calls[2]?.url).toBe('/api/query/script/lex');
  });
});

describe('history and scripts', () => {
  it('passes filters as query parameters', async () => {
    const { client, calls } = stubClient({ items: [] });

    await listQueryHistory({ connectionId: 'c1', q: 'select', limit: 10 }, client);
    await clearQueryHistory('c1', client);
    await listSavedScripts('/reports', client);

    expect(calls[0]?.url).toContain('connectionId=c1');
    expect(calls[0]?.url).toContain('q=select');
    expect(calls[1]?.init.method).toBe('DELETE');
    expect(calls[2]?.url).toContain('folder=%2Freports');
  });

  it('creates, updates and deletes scripts', async () => {
    const { client, calls } = stubClient({ id: 's1' });

    await createSavedScript({ name: 'n', cql: 'SELECT 1' }, client);
    await updateSavedScript('s1', { name: 'n', cql: 'SELECT 2' }, client);
    await deleteSavedScript('s1', client);

    expect(calls[0]?.init.method).toBe('POST');
    expect(calls[1]?.init.method).toBe('PUT');
    expect(calls[2]?.init.method).toBe('DELETE');
  });
});

describe('data tag', () => {
  it('uses the right verb for each row operation', async () => {
    const { client, calls } = stubClient({ executed: true, cql: 'INSERT ...' });

    await insertRow('c', 'demo', 'users', { values: { id: 1 } }, client);
    await updateRow('c', 'demo', 'users', { primaryKey: { id: 1 }, values: { a: 2 } }, client);
    await deleteRow('c', 'demo', 'users', { primaryKey: { id: 1 } }, client);

    expect(calls[0]?.init.method).toBe('POST');
    expect(calls[1]?.init.method).toBe('PATCH');
    expect(calls[2]?.init.method).toBe('DELETE');
    expect(calls[0]?.url).toBe('/api/connections/c/keyspaces/demo/tables/users/rows');
  });

  it('generates statements and checks editability', async () => {
    const { client, calls } = stubClient({ editable: false });

    await generateRowStatements(
      'c',
      'demo',
      'users',
      { statementKind: 'INSERT', rows: [{}] },
      client,
    );
    await checkRowEditability('c', 'demo', 'users', ['user_id'], 'rs_1', client);
    await checkRowEditability('c', 'demo', 'users', ['user_id'], undefined, client);

    expect(calls[0]?.url).toMatch(/rows\/statements$/);
    expect(calls[1]?.url).toMatch(/rows\/editability$/);
    expect(JSON.parse(String(calls[1]?.init.body))).toEqual({
      projectedColumns: ['user_id'],
      resultHandle: 'rs_1',
    });
    expect(JSON.parse(String(calls[2]?.init.body))).toEqual({ projectedColumns: ['user_id'] });
  });
});

describe('batch', () => {
  it('posts to the batch endpoint', async () => {
    const { client, calls } = stubClient({ assembledCql: 'BEGIN BATCH' });

    await executeBatch('c', { type: 'LOGGED', statements: [{ cql: 'INSERT ...' }] }, client);

    expect(calls[0]?.url).toBe('/api/connections/c/query/batch');
  });
});
