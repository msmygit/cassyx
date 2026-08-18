import { describe, expect, it, vi } from 'vitest';

/**
 * The shared `apiClient` singleton binds `globalThis.fetch` at module load, so the only way to
 * observe what the zero-argument call sites (and `defaultJobsApi`) send is to replace it.
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

vi.mock('../api/client', async (importOriginal) => {
  // Typed narrowly rather than with `typeof import(...)`: eslint forbids `import()` type
  // annotations, and the factory only needs the constructor.
  const actual = await importOriginal<{ ApiClient: typeof ApiClient }>();
  return {
    ...actual,
    apiClient: new actual.ApiClient({
      baseUrl: '',
      fetchImpl: sharedFetch as unknown as typeof fetch,
    }),
  };
});

import { ApiClient } from '../api/client';
import {
  cancelJob,
  createUnloadJob,
  defaultJobsApi,
  deleteJob,
  fetchJobLogs,
  getJob,
  jobArtifactUrl,
  jobFiltersToQuery,
  listJobs,
  type UnloadJobRequest,
} from './jobsApi';

const JOB_ID = '6c8f2a10-b4f9-4a1e-9a12-5f0a7e2d3b44';

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  });
}

function stubClient(body: unknown = { ok: true }, status = 200) {
  const fetchImpl = vi.fn(async () => jsonResponse(body, status));
  const client = new ApiClient({
    baseUrl: '',
    fetchImpl: fetchImpl as unknown as typeof fetch,
  });
  const lastCall = () => fetchImpl.mock.calls[0] as unknown as [string, RequestInit];
  return { client, fetchImpl, lastCall };
}

describe('jobFiltersToQuery', () => {
  it('is empty for empty filters', () => {
    expect(jobFiltersToQuery()).toEqual({});
    expect(jobFiltersToQuery({ status: [], type: [] })).toEqual({});
  });

  it('comma-joins array filters, per the contract style: form, explode: false', () => {
    expect(
      jobFiltersToQuery({
        status: ['QUEUED', 'RUNNING'],
        type: ['UNLOAD', 'LOAD'],
        connectionId: 'c1',
        limit: 50,
        offset: 0,
      }),
    ).toEqual({
      status: 'QUEUED,RUNNING',
      type: 'UNLOAD,LOAD',
      connectionId: 'c1',
      limit: 50,
      offset: 0,
    });
  });
});

describe('listJobs', () => {
  it('GETs /api/jobs with the encoded filters', async () => {
    const { client, lastCall } = stubClient({ items: [], total: 0, limit: 50, offset: 0 });

    const page = await listJobs({ status: ['RUNNING'], limit: 25 }, client);

    expect(page.total).toBe(0);
    const [url, init] = lastCall();
    expect(url).toBe('/api/jobs?status=RUNNING&limit=25');
    expect(init.method).toBe('GET');
  });

  it('sends no query string when unfiltered', async () => {
    const { client, lastCall } = stubClient({ items: [], total: 0, limit: 50, offset: 0 });
    await listJobs({}, client);
    expect(lastCall()[0]).toBe('/api/jobs');
  });
});

describe('getJob / cancelJob / deleteJob', () => {
  it('GETs one job with a URL-encoded id', async () => {
    const { client, lastCall } = stubClient({ id: JOB_ID });
    await getJob('a b/c', client);
    expect(lastCall()[0]).toBe('/api/jobs/a%20b%2Fc');
  });

  it('POSTs the cancel endpoint with no body', async () => {
    const { client, lastCall } = stubClient({ id: JOB_ID, status: 'RUNNING' });
    await cancelJob(JOB_ID, client);
    const [url, init] = lastCall();
    expect(url).toBe(`/api/jobs/${JOB_ID}/cancel`);
    expect(init.method).toBe('POST');
    expect(init.body).toBeUndefined();
  });

  it('DELETEs a job and tolerates the 204 empty body', async () => {
    const fetchImpl = vi.fn(async () => new Response(null, { status: 204 }));
    const client = new ApiClient({ baseUrl: '', fetchImpl: fetchImpl as unknown as typeof fetch });

    await expect(deleteJob(JOB_ID, client)).resolves.toBeUndefined();
    const [url, init] = fetchImpl.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe(`/api/jobs/${JOB_ID}`);
    expect(init.method).toBe('DELETE');
  });

  it('surfaces a problem+json failure as a rejection', async () => {
    const fetchImpl = vi.fn(
      async () =>
        new Response(JSON.stringify({ type: 'about:blank', title: 'Gone', status: 404 }), {
          status: 404,
          headers: { 'content-type': 'application/problem+json' },
        }),
    );
    const client = new ApiClient({ baseUrl: '', fetchImpl: fetchImpl as unknown as typeof fetch });
    await expect(getJob(JOB_ID, client)).rejects.toThrow();
  });
});

describe('fetchJobLogs', () => {
  it('passes tail and level through as query parameters', async () => {
    const { client, lastCall } = stubClient({ jobId: JOB_ID, lines: [] });
    await fetchJobLogs(JOB_ID, { tail: 200, level: 'WARN' }, client);
    expect(lastCall()[0]).toBe(`/api/jobs/${JOB_ID}/logs?tail=200&level=WARN`);
  });

  it('omits absent options so the server default applies', async () => {
    const { client, lastCall } = stubClient({ jobId: JOB_ID, lines: [] });
    await fetchJobLogs(JOB_ID, {}, client);
    expect(lastCall()[0]).toBe(`/api/jobs/${JOB_ID}/logs`);
  });
});

describe('createUnloadJob', () => {
  it('POSTs the request body to the connection-scoped path', async () => {
    const { client, lastCall } = stubClient({ id: JOB_ID, status: 'QUEUED' });
    const request: UnloadJobRequest = {
      name: 'Export demo.users',
      keyspace: 'demo',
      table: 'users',
      format: 'CSV',
      sink: { type: 'DOWNLOAD', compression: 'NONE' },
      engine: 'NATIVE',
    };

    await createUnloadJob('8f2b1c6e-2a55-4f47-9f2a-4c1c3f0d9a11', request, client);

    const [url, init] = lastCall();
    expect(url).toBe('/api/connections/8f2b1c6e-2a55-4f47-9f2a-4c1c3f0d9a11/jobs/unload');
    expect(init.method).toBe('POST');
    expect(JSON.parse(init.body as string)).toEqual(request);
  });
});

describe('jobArtifactUrl', () => {
  it('builds a URL and never performs a request — bulk bytes must not enter the tab heap', async () => {
    const { client, fetchImpl } = stubClient();
    expect(jobArtifactUrl(JOB_ID, undefined, client)).toBe(`/api/jobs/${JOB_ID}/artifact`);
    expect(jobArtifactUrl(JOB_ID, 'a1', client)).toBe(`/api/jobs/${JOB_ID}/artifact?artifactId=a1`);
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  it('encodes the job id', () => {
    const { client } = stubClient();
    expect(jobArtifactUrl('a b', undefined, client)).toBe('/api/jobs/a%20b/artifact');
  });
});

describe('defaultJobsApi', () => {
  it('routes every method through the shared client', async () => {
    sharedFetch.mockClear();

    await defaultJobsApi.listJobs({ status: ['QUEUED'] });
    await defaultJobsApi.getJob(JOB_ID);
    await defaultJobsApi.cancelJob(JOB_ID);
    await defaultJobsApi.deleteJob(JOB_ID);
    await defaultJobsApi.fetchLogs(JOB_ID, { tail: 10 });
    await defaultJobsApi.createUnloadJob('c1', {
      format: 'CSV',
      sink: { type: 'DOWNLOAD', compression: 'NONE' },
    });

    expect(sharedFetch.mock.calls.map((call) => (call as unknown as [string])[0])).toEqual([
      '/api/jobs?status=QUEUED',
      `/api/jobs/${JOB_ID}`,
      `/api/jobs/${JOB_ID}/cancel`,
      `/api/jobs/${JOB_ID}`,
      `/api/jobs/${JOB_ID}/logs?tail=10`,
      '/api/connections/c1/jobs/unload',
    ]);
    expect(defaultJobsApi.artifactUrl(JOB_ID, 'a1')).toBe(
      `/api/jobs/${JOB_ID}/artifact?artifactId=a1`,
    );
  });
});
