import { describe, expect, it, vi } from 'vitest';
import {
  isTerminalStatus,
  parseSseChunk,
  readSseStream,
  SseParser,
  subscribeToJobEvents,
} from './sse';

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

  it('knows which statuses are terminal', () => {
    expect(isTerminalStatus('RUNNING')).toBe(false);
    expect(isTerminalStatus('CANCELLED')).toBe(true);
    expect(isTerminalStatus('FAILED')).toBe(true);
    expect(isTerminalStatus('SUCCEEDED')).toBe(true);
  });
});
