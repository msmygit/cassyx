import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiClient, buildUrl } from './client';
import type { AppError } from './errors';
import {
  resetLicenseRequiredListeners,
  subscribeToLicenseRequired,
  type LicenseRequiredEvent,
} from './licenseSignal';

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  });
}

describe('buildUrl', () => {
  it('joins base and path and drops empty query params', () => {
    expect(buildUrl('http://api', 'api/health')).toBe('http://api/api/health');
    expect(buildUrl('', '/api/jobs', { limit: 10, cursor: undefined, refresh: false })).toBe(
      '/api/jobs?limit=10&refresh=false',
    );
  });
});

describe('ApiClient', () => {
  it('sends JSON bodies and decodes JSON responses', async () => {
    const fetchImpl = vi.fn(async () => jsonResponse({ ok: true }));
    const client = new ApiClient({ baseUrl: 'http://api/', fetchImpl });

    const result = await client.post<{ ok: boolean }>('/api/astra/databases', {
      astraToken: 'AstraCS:abc:def',
    });

    expect(result).toEqual({ ok: true });
    const [url, init] = fetchImpl.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe('http://api/api/astra/databases');
    expect(init.method).toBe('POST');
    expect((init.headers as Record<string, string>)['Content-Type']).toBe('application/json');
  });

  it('never puts a credential in the URL', async () => {
    const fetchImpl = vi.fn(async () => jsonResponse([]));
    const client = new ApiClient({ baseUrl: '', fetchImpl });
    await client.post('/api/astra/databases', { astraToken: 'AstraCS:secret:value' });
    const [url] = fetchImpl.mock.calls[0] as unknown as [string];
    expect(url).not.toContain('AstraCS');
  });

  it('throws a typed AppError carrying the problem body', async () => {
    const fetchImpl = vi.fn(
      async () =>
        new Response(JSON.stringify({ title: 'License required', status: 402 }), {
          status: 402,
          headers: { 'content-type': 'application/problem+json' },
        }),
    );
    const client = new ApiClient({ fetchImpl });

    await expect(client.get('/api/connections')).rejects.toMatchObject({
      status: 402,
      kind: 'http',
    });
  });

  it('keeps the query string out of the error label', async () => {
    const fetchImpl = vi.fn(async () => new Response(null, { status: 500 }));
    const client = new ApiClient({ fetchImpl });
    const error = await client.get('/api/jobs?token=leaky').catch((e: AppError) => e);
    expect((error as AppError).request).toBe('GET /api/jobs');
  });

  it('resolves 204 and non-JSON success bodies to undefined', async () => {
    const client = new ApiClient({
      fetchImpl: vi.fn(async () => new Response(null, { status: 204 })),
    });
    await expect(client.delete('/api/connections/1')).resolves.toBeUndefined();
  });

  it('surfaces malformed JSON as a parse error', async () => {
    const client = new ApiClient({
      fetchImpl: vi.fn(
        async () =>
          new Response('{broken', { status: 200, headers: { 'content-type': 'application/json' } }),
      ),
    });
    await expect(client.get('/api/license')).rejects.toMatchObject({ kind: 'parse' });
  });

  it('converts a transport failure into a network AppError', async () => {
    const client = new ApiClient({
      fetchImpl: vi.fn(async () => {
        throw new TypeError('Failed to fetch');
      }),
    });
    const error = await client.get('/api/health').catch((e: AppError) => e);
    expect((error as AppError).kind).toBe('network');
  });

  it('aborts on timeout', async () => {
    const client = new ApiClient({
      timeoutMs: 5,
      fetchImpl: (_url, init) =>
        new Promise((_resolve, reject) => {
          init?.signal?.addEventListener('abort', () =>
            reject(new DOMException('aborted', 'AbortError')),
          );
        }),
    });
    const error = await client.get('/api/health').catch((e: AppError) => e);
    expect((error as AppError).kind).toBe('aborted');
  });

  it('does not set a Content-Type for multipart uploads (the browser owns the boundary)', async () => {
    const fetchImpl = vi.fn(async () => jsonResponse({ uploadId: 'u1' }));
    const client = new ApiClient({ fetchImpl });
    const form = new FormData();
    form.append('bundle', new Blob(['zip']), 'scb.zip');

    await client.upload('/api/connections/scb', form);
    const [, init] = fetchImpl.mock.calls[0] as unknown as [string, RequestInit];
    expect((init.headers as Record<string, string>)['Content-Type']).toBeUndefined();
  });

  it('exposes absolute URLs for streaming endpoints', () => {
    const client = new ApiClient({ baseUrl: 'http://api' });
    expect(client.url('/api/jobs/1/events')).toBe('http://api/api/jobs/1/events');
  });
});

/** plan §9.1 — any gated call can 402, so the transport is where it gets announced. */
describe('license gate signal', () => {
  afterEach(() => {
    resetLicenseRequiredListeners();
  });

  it('publishes a 402 to every subscriber, with the parsed state', async () => {
    const seen: LicenseRequiredEvent[] = [];
    subscribeToLicenseRequired((event) => seen.push(event));

    const client = new ApiClient({
      fetchImpl: vi.fn(
        async () =>
          new Response(
            JSON.stringify({ title: 'License required', status: 402, state: 'ABSENT' }),
            { status: 402, headers: { 'content-type': 'application/problem+json' } },
          ),
      ),
    });
    await client.get('/api/connections').catch(() => undefined);

    expect(seen).toEqual([
      {
        state: 'ABSENT',
        detail: null,
        invitesPurchase: false,
        unlockHint: null,
        request: 'GET /api/connections',
      },
    ]);
  });

  it('publishes nothing for any other failure', async () => {
    const listener = vi.fn();
    subscribeToLicenseRequired(listener);
    const client = new ApiClient({
      fetchImpl: vi.fn(async () => new Response(null, { status: 500 })),
    });
    await client.get('/api/connections').catch(() => undefined);
    expect(listener).not.toHaveBeenCalled();
  });

  it('stops delivering after unsubscribe and still rejects the caller', async () => {
    const listener = vi.fn();
    subscribeToLicenseRequired(listener)();
    const client = new ApiClient({
      fetchImpl: vi.fn(async () => new Response(null, { status: 402 })),
    });
    await expect(client.get('/api/connections')).rejects.toMatchObject({ status: 402 });
    expect(listener).not.toHaveBeenCalled();
  });

  it('lets the original 402 through even when a subscriber throws', async () => {
    const good = vi.fn();
    subscribeToLicenseRequired(() => {
      throw new Error('subscriber is broken');
    });
    subscribeToLicenseRequired(good);
    const client = new ApiClient({
      fetchImpl: vi.fn(async () => new Response(null, { status: 402 })),
    });
    await expect(client.get('/api/connections')).rejects.toMatchObject({ status: 402 });
    expect(good).toHaveBeenCalledOnce();
  });
});
