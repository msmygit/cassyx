import { describe, expect, it, vi } from 'vitest';
import { ApiClient } from '../api/client';
import {
  connectConnection,
  createConnection,
  deleteConnection,
  disconnectConnection,
  getClusterCapabilities,
  getConnectionHealth,
  listConnections,
  testConnection,
  updateConnection,
  uploadKeystore,
  uploadSecureConnectBundle,
  uploadTruststore,
  type ConnectionRequest,
} from './connectionsApi';

interface Call {
  url: string;
  init: RequestInit;
}

/** Indexed access with a real failure message, since `noUncheckedIndexedAccess` is on. */
function at(calls: Call[], index: number): Call {
  const call = calls[index];
  if (!call) throw new Error(`expected at least ${index + 1} request(s), saw ${calls.length}`);
  return call;
}

function stubClient(body: unknown = {}): { client: ApiClient; calls: Call[] } {
  const calls: Call[] = [];
  const fetchImpl = vi.fn(async (url: string | URL | Request, init?: RequestInit) => {
    calls.push({ url: String(url), init: init ?? {} });
    return new Response(JSON.stringify(body), {
      status: 200,
      headers: { 'content-type': 'application/json' },
    });
  });
  return {
    client: new ApiClient({ baseUrl: '', fetchImpl: fetchImpl as unknown as typeof fetch }),
    calls,
  };
}

const request: ConnectionRequest = {
  name: 'local-dev',
  mode: 'CASSANDRA',
  requestTimeoutMillis: 10_000,
  contactPoints: [{ host: '127.0.0.1', port: 9042 }],
  localDatacenter: 'datacenter1',
  password: 'hunter2-the-real-password',
};

describe('connectionsApi', () => {
  it('uses the contract paths and verbs', async () => {
    const { client, calls } = stubClient([]);

    await listConnections(client);
    await createConnection(request, client);
    await updateConnection('c1', request, client);
    await deleteConnection('c1', client);
    await connectConnection('c1', client);
    await disconnectConnection('c1', client);
    await getConnectionHealth('c1', client);

    expect(calls.map((call) => `${call.init.method} ${call.url}`)).toEqual([
      'GET /api/connections',
      'POST /api/connections',
      'PUT /api/connections/c1',
      'DELETE /api/connections/c1',
      'POST /api/connections/c1/connect',
      'POST /api/connections/c1/disconnect',
      'GET /api/connections/c1/health',
    ]);
  });

  it('passes refresh as a query parameter on the capability probe', async () => {
    const { client, calls } = stubClient({});

    await getClusterCapabilities('c1', true, client);

    expect(at(calls, 0).url).toBe('/api/connections/c1/capabilities?refresh=true');
  });

  it('escapes connection ids so a hostile id cannot forge a path', async () => {
    const { client, calls } = stubClient({});

    await getConnectionHealth('../../admin', client);

    expect(at(calls, 0).url).toBe('/api/connections/..%2F..%2Fadmin/health');
  });

  /**
   * The single most important assertion in this file: a credential in a query string ends up in
   * access logs, proxy logs, browser history and `Referer` headers.
   */
  it('never puts a credential in a URL', async () => {
    const { client, calls } = stubClient({});

    await createConnection(request, client);
    await testConnection({ connection: request }, client);

    for (const call of calls) {
      expect(call.url).not.toContain('hunter2-the-real-password');
    }
    expect(String(at(calls, 0).init.body)).toContain('hunter2-the-real-password');
  });

  it('sends a multipart body for the secure connect bundle', async () => {
    const { client, calls } = stubClient({});
    const file = new File(['zip-bytes'], 'secure-connect-prod.zip', { type: 'application/zip' });

    await uploadSecureConnectBundle('c1', file, client);

    expect(at(calls, 0).url).toBe('/api/connections/c1/secure-connect-bundle');
    expect(at(calls, 0).init.body).toBeInstanceOf(FormData);
    expect((at(calls, 0).init.body as FormData).get('file')).toBe(file);
    // The browser sets the multipart boundary; forcing a Content-Type here breaks the upload.
    expect((at(calls, 0).init.headers as Record<string, string>)['Content-Type']).toBeUndefined();
  });

  it('carries the store password in the multipart body, never the URL', async () => {
    const { client, calls } = stubClient({});
    const file = new File(['jks'], 'truststore.jks');

    await uploadTruststore('c1', { file, password: 'store-secret', storeType: 'JKS' }, client);
    await uploadKeystore('c1', { file }, client);

    const body = at(calls, 0).init.body as FormData;
    expect(body.get('password')).toBe('store-secret');
    expect(body.get('storeType')).toBe('JKS');
    expect(at(calls, 0).url).not.toContain('store-secret');
    // No password supplied means the field is omitted, not sent empty.
    expect((at(calls, 1).init.body as FormData).get('password')).toBeNull();
  });

  it('supports testing a saved connection by id as well as unsaved input', async () => {
    const { client, calls } = stubClient({ success: true, elapsedMillis: 1 });

    await testConnection({ connectionId: 'c1' }, client);

    expect(at(calls, 0).url).toBe('/api/connections/test');
    expect(JSON.parse(String(at(calls, 0).init.body))).toEqual({ connectionId: 'c1' });
  });
});
