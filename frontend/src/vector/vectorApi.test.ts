import { describe, expect, it, vi } from 'vitest';
import { ApiClient } from '../api/client';
import {
  addVectorColumn,
  alterSaiIndex,
  buildAnnQuery,
  computeSimilarity,
  createSaiIndex,
  dropSaiIndex,
  executeAnnQuery,
  getSaiIndexStatus,
  listSaiIndexes,
  listVectorColumns,
  vectorQueryKeys,
} from './vectorApi';
import type { AnnQueryRequest } from './types';

interface Call {
  url: string;
  method: string;
  body: unknown;
}

/** Indexed reads are `| undefined` under noUncheckedIndexedAccess; fail loudly instead. */
function call(calls: Call[], index: number): Call {
  const found = calls[index];
  if (!found) throw new Error(`Expected a request at index ${index}, saw ${calls.length}`);
  return found;
}

function stubClient(): { client: ApiClient; calls: Call[] } {
  const calls: Call[] = [];
  const fetchImpl = vi.fn(async (url: string | URL | Request, init?: RequestInit) => {
    calls.push({
      url: String(url),
      method: init?.method ?? 'GET',
      body: init?.body ? JSON.parse(init.body as string) : undefined,
    });
    return new Response('[]', { status: 200, headers: { 'content-type': 'application/json' } });
  }) as unknown as typeof fetch;

  return { client: new ApiClient({ baseUrl: '', fetchImpl }), calls };
}

describe('vectorApi', () => {
  it('builds the contract paths for every vector operation', async () => {
    const { client, calls } = stubClient();

    await listVectorColumns('c1', 'demo', 'doc_embeddings', client);
    await listSaiIndexes('c1', 'demo', 'doc_embeddings', client);
    await getSaiIndexStatus('c1', 'demo', 'doc_embeddings', 'ann', client);

    expect(calls.map((call) => call.url)).toEqual([
      '/api/connections/c1/keyspaces/demo/tables/doc_embeddings/vector-columns',
      '/api/connections/c1/keyspaces/demo/tables/doc_embeddings/sai-indexes',
      '/api/connections/c1/keyspaces/demo/tables/doc_embeddings/sai-indexes/ann',
    ]);
  });

  it('percent-encodes identifiers so a quoted keyspace cannot break the path', async () => {
    const { client, calls } = stubClient();

    await listVectorColumns('c/1', 'My Keyspace', 'Doc Chunks', client);

    expect(call(calls, 0).url).toBe(
      '/api/connections/c%2F1/keyspaces/My%20Keyspace/tables/Doc%20Chunks/vector-columns',
    );
  });

  it('sends the right methods and bodies for the mutating operations', async () => {
    const { client, calls } = stubClient();

    await addVectorColumn(
      'c1',
      'demo',
      'docs',
      { name: 'embedding', dimensions: 1536, createIndex: true, elementType: 'float' },
      client,
    );
    await createSaiIndex(
      'c1',
      'demo',
      'docs',
      { name: 'docs_ann', target: 'embedding', similarityFunction: 'cosine', ifNotExists: true },
      client,
    );
    await alterSaiIndex(
      'c1',
      'demo',
      'docs',
      'docs_ann',
      { name: 'docs_ann', target: 'embedding', similarityFunction: 'euclidean', ifNotExists: true },
      client,
    );
    await dropSaiIndex('c1', 'demo', 'docs', 'docs_ann', false, client);

    expect(calls.map((call) => call.method)).toEqual(['POST', 'POST', 'PUT', 'DELETE']);
    expect(call(calls, 0).body).toMatchObject({ dimensions: 1536, createIndex: true });
    expect(call(calls, 2).body).toMatchObject({ similarityFunction: 'euclidean' });
    expect(call(calls, 3).url).toContain('ifExists=false');
  });

  it('separates generate-only from execute', async () => {
    const { client, calls } = stubClient();
    const request: AnnQueryRequest = {
      keyspace: 'demo',
      table: 'docs',
      vectorColumn: 'embedding',
      queryVector: { values: [0.1, 0.2] },
      limit: 5,
      includeVectorColumn: false,
      fetchSize: 500,
    };

    await buildAnnQuery('c1', request, client);
    await executeAnnQuery('c1', request, client);

    expect(call(calls, 0).url).toBe('/api/connections/c1/vector/ann-query');
    expect(call(calls, 1).url).toBe('/api/connections/c1/vector/ann-query/execute');
    expect(call(calls, 0).body).toEqual(request);
  });

  it('computes similarity server-side so the browser never ships float arrays around', async () => {
    const { client, calls } = stubClient();

    await computeSimilarity(
      'c1',
      { left: { values: [0.1] }, right: { values: [0.2] }, functions: ['cosine'] },
      client,
    );

    expect(call(calls, 0).url).toBe('/api/connections/c1/vector/similarity');
    expect(call(calls, 0).method).toBe('POST');
  });

  it('exposes stable query keys for cache invalidation after DDL', () => {
    expect(vectorQueryKeys.saiIndexes('c1', 'demo', 'docs')).toEqual([
      'vector',
      'sai-indexes',
      'c1',
      'demo',
      'docs',
    ]);
    expect(vectorQueryKeys.vectorColumns('c1', 'demo', 'docs')).toContain('columns');
    expect(vectorQueryKeys.saiIndexStatus('c1', 'demo', 'docs', 'ann')).toContain('ann');
  });
});
