import { describe, expect, it, vi } from 'vitest';
import { ApiClient } from '../../api/client';
import type * as ClientModuleNamespace from '../../api/client';

type ClientModule = typeof ClientModuleNamespace;

/**
 * The shared `apiClient` singleton binds `globalThis.fetch` at module load, so the only way to
 * observe what `defaultDsbulkApi` sends is to replace the singleton itself.
 */
const sharedFetch = vi.hoisted(() =>
  vi.fn(
    async () =>
      new Response(JSON.stringify({ ok: true }), {
        status: 200,
        headers: { 'content-type': 'application/json' },
      }),
  ),
);

vi.mock('../../api/client', async (importOriginal) => {
  const actual = await importOriginal<ClientModule>();
  return {
    ...actual,
    apiClient: new actual.ApiClient({
      baseUrl: '',
      fetchImpl: sharedFetch as unknown as typeof fetch,
    }),
  };
});
import {
  UPLOAD_TIMEOUT_MS,
  createCountJob,
  createJobTemplate,
  createLoadJob,
  defaultDsbulkApi,
  deleteJobTemplate,
  deriveBulkDefaults,
  listJobTemplates,
  previewBulkCommand,
  updateJobTemplate,
  uploadBulkSourceFile,
} from './dsbulkApi';

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  });
}

function stub(body: unknown = {}, status = 200) {
  const fetchImpl = vi.fn(async () => jsonResponse(body, status));
  return { fetchImpl, client: new ApiClient({ baseUrl: '', fetchImpl }) };
}

function callOf(fetchImpl: ReturnType<typeof vi.fn>, index = 0): [string, RequestInit] {
  return fetchImpl.mock.calls[index] as unknown as [string, RequestInit];
}

describe('dsbulkApi', () => {
  it('derives defaults against the connection-scoped endpoint', async () => {
    const { fetchImpl, client } = stub({ operation: 'UNLOAD', settings: [], probe: {} });
    const response = await deriveBulkDefaults(
      'conn-1',
      { operation: 'UNLOAD', keyspace: 'demo', table: 'users' },
      client,
    );

    const [url, init] = callOf(fetchImpl);
    expect(url).toBe('/api/connections/conn-1/bulk/defaults');
    expect(init.method).toBe('POST');
    expect(JSON.parse(String(init.body))).toEqual({
      operation: 'UNLOAD',
      keyspace: 'demo',
      table: 'users',
    });
    expect(response.operation).toBe('UNLOAD');
  });

  it('escapes the connection id in the path', async () => {
    const { fetchImpl, client } = stub({ command: '', argv: [], hocon: '' });
    await previewBulkCommand('conn/../evil', { operation: 'LOAD' }, client);
    expect(callOf(fetchImpl)[0]).toBe('/api/connections/conn%2F..%2Fevil/bulk/command-preview');
  });

  it('posts load and count jobs', async () => {
    const { fetchImpl, client } = stub({ id: 'job-1' });
    await createLoadJob(
      'conn-1',
      {
        keyspace: 'demo',
        table: 'users',
        source: { uploadId: 'up_1', compression: 'AUTO' },
        dryRun: false,
      },
      client,
    );
    await createCountJob('conn-1', { keyspace: 'demo', table: 'users', topPartitions: 10 }, client);

    expect(callOf(fetchImpl, 0)[0]).toBe('/api/connections/conn-1/jobs/load');
    expect(callOf(fetchImpl, 1)[0]).toBe('/api/connections/conn-1/jobs/count');
  });

  it('never puts an S3 secret in the URL', async () => {
    const { fetchImpl, client } = stub({ command: '', argv: [], hocon: '' });
    await previewBulkCommand(
      'conn-1',
      {
        operation: 'LOAD',
        dsbulkSettings: { s3: { secretAccessKey: 'super-secret-value' } },
      },
      client,
    );
    const [url, init] = callOf(fetchImpl);
    expect(url).not.toContain('super-secret-value');
    expect(String(init.body)).toContain('super-secret-value');
  });

  it('uploads a source file as multipart with a long timeout', async () => {
    const { fetchImpl, client } = stub({ uploadId: 'up_1', fileName: 'users.csv' }, 201);
    const file = new File(['a,b\n1,2\n'], 'users.csv', { type: 'text/csv' });

    const upload = await uploadBulkSourceFile(file, 'CSV', client);

    const [url, init] = callOf(fetchImpl);
    expect(url).toBe('/api/bulk/uploads');
    expect(init.body).toBeInstanceOf(FormData);
    const form = init.body as FormData;
    expect(form.get('format')).toBe('CSV');
    expect((form.get('file') as File).name).toBe('users.csv');
    // The browser must set the multipart boundary itself.
    expect((init.headers as Record<string, string>)['Content-Type']).toBeUndefined();
    expect(upload.uploadId).toBe('up_1');
    expect(UPLOAD_TIMEOUT_MS).toBeGreaterThan(60_000);
  });

  it('omits the format part when it is not known', async () => {
    const { fetchImpl, client } = stub({ uploadId: 'up_2', fileName: 'users.dat' }, 201);
    await uploadBulkSourceFile(new File(['x'], 'users.dat'), undefined, client);
    expect((callOf(fetchImpl)[1].body as FormData).get('format')).toBeNull();
  });

  it('covers the job-template CRUD surface', async () => {
    const { fetchImpl, client } = stub([]);
    await listJobTemplates(client);
    await createJobTemplate(
      { name: 'Fast unload', operation: 'UNLOAD', dsbulkSettings: {} },
      client,
    );
    await updateJobTemplate(
      'tpl 1',
      { name: 'Fast unload', operation: 'UNLOAD', dsbulkSettings: {} },
      client,
    );
    await deleteJobTemplate('tpl 1', client);

    expect(fetchImpl.mock.calls.map((call) => (call as unknown as [string])[0])).toEqual([
      '/api/job-templates',
      '/api/job-templates',
      '/api/job-templates/tpl%201',
      '/api/job-templates/tpl%201',
    ]);
    expect(callOf(fetchImpl, 2)[1].method).toBe('PUT');
    expect(callOf(fetchImpl, 3)[1].method).toBe('DELETE');
  });

  it('exposes a transport seam wired to the default client', () => {
    expect(Object.keys(defaultDsbulkApi).sort()).toEqual([
      'createCountJob',
      'createLoadJob',
      'deriveDefaults',
      'previewCommand',
      'uploadSourceFile',
    ]);
  });

  it('routes every seam method through its endpoint function', async () => {
    const fetchImpl = sharedFetch;
    fetchImpl.mockClear();

    await defaultDsbulkApi.deriveDefaults('c', { operation: 'LOAD' });
    await defaultDsbulkApi.previewCommand('c', { operation: 'LOAD' });
    await defaultDsbulkApi.createLoadJob('c', {
      keyspace: 'k',
      table: 't',
      source: { path: '/tmp/x', compression: 'AUTO' },
      dryRun: false,
    });
    await defaultDsbulkApi.createCountJob('c', { keyspace: 'k', table: 't', topPartitions: 10 });
    await defaultDsbulkApi.uploadSourceFile(new File(['x'], 'x.csv'), 'CSV');

    expect(fetchImpl.mock.calls.map((call) => (call as unknown as [string])[0])).toEqual([
      '/api/connections/c/bulk/defaults',
      '/api/connections/c/bulk/command-preview',
      '/api/connections/c/jobs/load',
      '/api/connections/c/jobs/count',
      '/api/bulk/uploads',
    ]);
  });
});
