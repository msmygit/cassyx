import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  isTerminalStatus,
  MAX_SSE_RECONNECT_ERRORS,
  parseSseChunk,
  probeSseGate,
  readSseStream,
  SseParser,
  subscribeToJobEvents,
} from './sse';
import { problemFromResponse } from './errors';
import {
  resetLicenseRequiredListeners,
  subscribeToLicenseRequired,
  type LicenseRequiredEvent,
} from './licenseSignal';

afterEach(() => {
  resetLicenseRequiredListeners();
});

describe('parseSseChunk', () => {
  it('parses event, data, id and retry fields', () => {
    const message = parseSseChunk('event: progress\ndata: {"a":1}\nid: 7\nretry: 2000');
    expect(message).toEqual({ event: 'progress', data: '{"a":1}', id: '7', retry: 2000 });
  });

  it('joins multi-line data and defaults the event name', () => {
    expect(parseSseChunk('data: line1\ndata: line2')).toEqual({
      event: 'message',
      data: 'line1\nline2',
    });
  });

  it('ignores comment-only heartbeat blocks', () => {
    expect(parseSseChunk(': keep-alive')).toBeNull();
  });

  it('ignores unknown fields and a non-numeric retry', () => {
    const message = parseSseChunk('data: x\nfoo: bar\nretry: soon');
    expect(message?.retry).toBeUndefined();
    expect(message?.data).toBe('x');
  });
});

describe('SseParser', () => {
  it('emits only complete messages and buffers the remainder', () => {
    const parser = new SseParser();
    expect(parser.push('data: one\n\ndata: par')).toEqual([{ event: 'message', data: 'one' }]);
    expect(parser.pending).toBe('data: par');
    expect(parser.push('tial\n\n')).toEqual([{ event: 'message', data: 'partial' }]);
    expect(parser.pending).toBe('');
  });

  it('normalises CRLF line endings', () => {
    const parser = new SseParser();
    expect(parser.push('event: progress\r\ndata: ok\r\n\r\n')).toEqual([
      { event: 'progress', data: 'ok' },
    ]);
  });
});

describe('readSseStream', () => {
  it('decodes a byte stream into messages', async () => {
    const encoder = new TextEncoder();
    const body = new ReadableStream({
      start(controller) {
        controller.enqueue(encoder.encode('data: {"jobId":"1"}\n\n'));
        controller.enqueue(encoder.encode(': heartbeat\n\ndata: done\n\n'));
        controller.close();
      },
    });
    const messages: string[] = [];
    await readSseStream(new Response(body), (message) => messages.push(message.data));
    expect(messages).toEqual(['{"jobId":"1"}', 'done']);
  });

  it('rejects when the response has no body', async () => {
    await expect(readSseStream(new Response(null, { status: 204 }), () => {})).rejects.toThrow(
      /no readable body/i,
    );
  });
});

describe('probeSseGate', () => {
  it('reports the licence problem behind a 402', async () => {
    const fetchImpl = vi.fn(
      async () =>
        new Response(JSON.stringify({ title: 'License required', status: 402, state: 'ABSENT' }), {
          status: 402,
          headers: { 'content-type': 'application/problem+json' },
        }),
    );
    const error = await probeSseGate('/api/jobs/1/events', fetchImpl);
    expect(error?.licenseRequired?.state).toBe('ABSENT');
    const [, init] = fetchImpl.mock.calls[0] as unknown as [string, RequestInit];
    expect((init.headers as Record<string, string>).Accept).toBe('text/event-stream');
  });

  it('abandons the response when the stream is healthy again', async () => {
    let aborted = false;
    const fetchImpl = vi.fn(async (_url: string | URL | Request, init?: RequestInit) => {
      init?.signal?.addEventListener('abort', () => {
        aborted = true;
      });
      return new Response('data: hi\n\n', {
        status: 200,
        headers: { 'content-type': 'text/event-stream' },
      });
    }) as unknown as typeof fetch;

    expect(await probeSseGate('/api/jobs/1/events', fetchImpl)).toBeNull();
    expect(aborted).toBe(true);
  });

  it('answers "do not know" when the probe itself fails', async () => {
    const fetchImpl = vi.fn(async () => {
      throw new TypeError('Failed to fetch');
    }) as unknown as typeof fetch;
    expect(await probeSseGate('/api/jobs/1/events', fetchImpl)).toBeNull();
  });
});

/** Minimal EventSource double supporting the contract's NAMED events. */
class FakeEventSource {
  onerror: (() => void) | null = null;
  readyState = 1;
  closed = false;
  private listeners = new Map<string, ((event: MessageEvent<string>) => void)[]>();

  constructor(readonly url: string) {}

  addEventListener(name: string, listener: (event: MessageEvent<string>) => void) {
    const existing = this.listeners.get(name) ?? [];
    existing.push(listener);
    this.listeners.set(name, existing);
  }

  close() {
    this.closed = true;
    this.readyState = 2;
  }

  emit(name: string, payload: unknown) {
    const data = typeof payload === 'string' ? payload : JSON.stringify(payload);
    for (const listener of this.listeners.get(name) ?? []) {
      listener({ data } as MessageEvent<string>);
    }
  }
}

function subscribe(handlers: Parameters<typeof subscribeToJobEvents>[1]) {
  let source!: FakeEventSource;
  const subscription = subscribeToJobEvents('job-1', handlers, {
    url: '/api/jobs/job-1/events',
    // Default is a real fetch probe; these cases are about the EventSource layer alone.
    probe: async () => null,
    factory: (url) => {
      source = new FakeEventSource(url);
      return source as unknown as EventSource;
    },
  });
  return { source, subscription };
}

describe('subscribeToJobEvents', () => {
  it('routes each named event to its handler', () => {
    const onStatus = vi.fn();
    const onProgress = vi.fn();
    const onLog = vi.fn();
    const { source } = subscribe({ onStatus, onProgress, onLog });

    source.emit('status', { jobId: 'job-1', status: 'RUNNING', at: '2026-08-17T10:00:00Z' });
    source.emit('progress', { jobId: 'job-1', percent: 42, rowsProcessed: 4200 });
    source.emit('log', {
      jobId: 'job-1',
      level: 'INFO',
      message: 'split 12/10000 done',
      at: '2026-08-17T10:00:01Z',
    });

    expect(onStatus).toHaveBeenCalledWith(expect.objectContaining({ status: 'RUNNING' }));
    expect(onProgress).toHaveBeenCalledWith(expect.objectContaining({ percent: 42 }));
    expect(onLog).toHaveBeenCalledWith(expect.objectContaining({ message: 'split 12/10000 done' }));
    expect(source.closed).toBe(false);
  });

  it('closes the stream on `completed` so a finished job leaks no connection', () => {
    const onComplete = vi.fn();
    const { source } = subscribe({ onComplete });

    source.emit('completed', { jobId: 'job-1', status: 'SUCCEEDED', rowsProcessed: 10_000_000 });

    expect(onComplete).toHaveBeenCalledOnce();
    expect(source.closed).toBe(true);
  });

  it('closes the stream on a terminal status event', () => {
    const { source } = subscribe({});
    source.emit('status', { jobId: 'job-1', status: 'CANCELLED', at: '2026-08-17T10:00:02Z' });
    expect(source.closed).toBe(true);
  });

  it('surfaces a job-level error without closing — the job may still recover', () => {
    const onJobError = vi.fn();
    const { source } = subscribe({ onJobError });

    source.emit('error', {
      jobId: 'job-1',
      problem: { type: 'about:blank', title: 'Split failed', status: 500 },
      recoverable: true,
    });

    expect(onJobError).toHaveBeenCalledWith(expect.objectContaining({ recoverable: true }));
    expect(source.closed).toBe(false);
  });

  it('ignores unparseable payloads', () => {
    const onProgress = vi.fn();
    const { source } = subscribe({ onProgress });
    source.emit('progress', 'not json');
    expect(onProgress).not.toHaveBeenCalled();
  });

  it('passes every raw event through onMessage', () => {
    const onMessage = vi.fn();
    const { source } = subscribe({ onMessage });
    source.emit('progress', { jobId: 'job-1', percent: 1 });
    expect(onMessage).toHaveBeenCalledWith(expect.objectContaining({ event: 'progress' }));
  });

  it('reports a transport error only once the stream is definitively closed', () => {
    const onError = vi.fn();
    const { source } = subscribe({ onError });

    source.onerror?.(); // transient: EventSource reconnects by itself
    expect(onError).not.toHaveBeenCalled();

    source.readyState = 2;
    source.onerror?.();
    expect(onError).toHaveBeenCalledOnce();
  });

  it('gives up rather than reconnecting forever against a wall', () => {
    const onError = vi.fn();
    const { source } = subscribe({ onError });

    // readyState stays CONNECTING: some implementations retry a refused stream indefinitely.
    for (let i = 0; i < MAX_SSE_RECONNECT_ERRORS; i += 1) source.onerror?.();

    expect(onError).toHaveBeenCalledOnce();
    expect(source.closed).toBe(true);
  });

  it('probes once for the reason and reports a licence wall', async () => {
    const onLicenseRequired = vi.fn();
    const seen: LicenseRequiredEvent[] = [];
    subscribeToLicenseRequired((event) => seen.push(event));
    const probe = vi.fn(
      async () =>
        await problemFromResponse(
          new Response(
            JSON.stringify({ title: 'License required', status: 402, state: 'EXPIRED' }),
            {
              status: 402,
              headers: { 'content-type': 'application/problem+json' },
            },
          ),
          'SSE /api/jobs',
        ),
    );

    let source!: FakeEventSource;
    subscribeToJobEvents(
      'job-1',
      { onLicenseRequired },
      {
        url: '/api/jobs/job-1/events',
        probe,
        factory: (url) => {
          source = new FakeEventSource(url);
          return source as unknown as EventSource;
        },
      },
    );

    source.readyState = 2;
    source.onerror?.();
    source.onerror?.(); // a second failure must not mean a second probe
    await vi.waitFor(() => expect(onLicenseRequired).toHaveBeenCalledOnce());

    expect(probe).toHaveBeenCalledOnce();
    expect(seen).toEqual([
      {
        state: 'EXPIRED',
        detail: null,
        invitesPurchase: false,
        unlockHint: null,
        request: 'SSE /api/jobs',
      },
    ]);
  });

  it('stays quiet when the stream died for some other reason', async () => {
    const onLicenseRequired = vi.fn();
    const listener = vi.fn();
    subscribeToLicenseRequired(listener);
    const probe = vi.fn(async () => null);

    let source!: FakeEventSource;
    subscribeToJobEvents(
      'job-1',
      { onLicenseRequired },
      {
        url: '/api/jobs/job-1/events',
        probe,
        factory: (url) => {
          source = new FakeEventSource(url);
          return source as unknown as EventSource;
        },
      },
    );

    source.readyState = 2;
    source.onerror?.();
    await vi.waitFor(() => expect(probe).toHaveBeenCalledOnce());
    expect(onLicenseRequired).not.toHaveBeenCalled();
    expect(listener).not.toHaveBeenCalled();
  });

  it('knows which statuses are terminal', () => {
    expect(isTerminalStatus('RUNNING')).toBe(false);
    expect(isTerminalStatus('CANCELLED')).toBe(true);
    expect(isTerminalStatus('FAILED')).toBe(true);
    expect(isTerminalStatus('SUCCEEDED')).toBe(true);
  });
});
