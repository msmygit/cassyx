import { act, renderHook, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { Job } from './jobsApi';
import { DEFAULT_MAX_LOG_LINES, useJobEvents } from './useJobEvents';

const JOB_ID = '6c8f2a10-b4f9-4a1e-9a12-5f0a7e2d3b44';

function makeJob(overrides: Partial<Job> = {}): Job {
  return {
    id: JOB_ID,
    name: 'Export demo.users',
    type: 'UNLOAD',
    status: 'RUNNING',
    engine: 'NATIVE',
    createdAt: '2026-08-17T12:00:00Z',
    ...overrides,
  };
}

/**
 * EventSource double.
 *
 * It deliberately has NO `onmessage` support: the contract's stream uses named events
 * (`status`, `progress`, `log`, `completed`, `error`), so a subscriber that listens on the default
 * `message` event receives nothing at all. Modelling only `addEventListener` keeps that trap
 * visible in the test rather than passing accidentally.
 */
class FakeEventSource {
  static instances: FakeEventSource[] = [];

  onerror: (() => void) | null = null;
  readyState = 1;
  closed = false;
  private listeners = new Map<string, ((event: MessageEvent<string>) => void)[]>();

  constructor(readonly url: string) {
    FakeEventSource.instances.push(this);
  }

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

  /** No listener is ever registered for `message`, mirroring the real stream's named events. */
  emitDefaultMessage(payload: unknown) {
    this.emit('message', payload);
  }
}

function factory(url: string): EventSource {
  return new FakeEventSource(url) as unknown as EventSource;
}

function latest(): FakeEventSource {
  const source = FakeEventSource.instances.at(-1);
  if (!source) throw new Error('no EventSource was opened');
  return source;
}

beforeEach(() => {
  FakeEventSource.instances = [];
});

describe('useJobEvents', () => {
  it('opens a stream for the job id and reports streaming', () => {
    const { result } = renderHook(() => useJobEvents(makeJob(), { factory }));

    expect(FakeEventSource.instances).toHaveLength(1);
    expect(latest().url).toContain(`/api/jobs/${JOB_ID}/events`);
    expect(result.current.streaming).toBe(true);
    expect(result.current.job?.status).toBe('RUNNING');
  });

  it('uses the server-supplied eventsUrl when given one', () => {
    renderHook(() => useJobEvents(makeJob(), { factory, url: '/custom/jobs/x/events' }));
    expect(latest().url).toBe('/custom/jobs/x/events');
  });

  it('opens nothing when disabled or when there is no job', () => {
    const disabled = renderHook(() => useJobEvents(makeJob(), { factory, enabled: false }));
    expect(FakeEventSource.instances).toHaveLength(0);
    expect(disabled.result.current.streaming).toBe(false);

    const none = renderHook(() => useJobEvents(null, { factory }));
    expect(FakeEventSource.instances).toHaveLength(0);
    expect(none.result.current.job).toBeNull();
  });

  it('applies named progress events onto the job', () => {
    const { result } = renderHook(() => useJobEvents(makeJob(), { factory }));

    act(() => {
      latest().emit('progress', {
        jobId: JOB_ID,
        rowsProcessed: 4210000,
        percent: 42.1,
        rowsPerSecond: 186420,
        etaMillis: 31100,
      });
    });

    expect(result.current.job?.progress).toMatchObject({ percent: 42.1, rowsPerSecond: 186420 });
  });

  it('ignores a payload delivered on the default `message` event', () => {
    const { result } = renderHook(() => useJobEvents(makeJob(), { factory }));

    act(() => {
      latest().emitDefaultMessage({ jobId: JOB_ID, percent: 99 });
    });

    expect(result.current.job?.progress).toBeUndefined();
  });

  it('applies status transitions and stops streaming on a terminal status', () => {
    const { result } = renderHook(() => useJobEvents(makeJob({ status: 'QUEUED' }), { factory }));

    act(() => {
      latest().emit('status', { jobId: JOB_ID, status: 'RUNNING', at: '2026-08-17T12:00:03Z' });
    });
    expect(result.current.job?.status).toBe('RUNNING');
    expect(result.current.streaming).toBe(true);

    act(() => {
      latest().emit('status', { jobId: JOB_ID, status: 'FAILED', at: '2026-08-17T12:00:53Z' });
    });
    expect(result.current.job?.status).toBe('FAILED');
    expect(result.current.job?.finishedAt).toBe('2026-08-17T12:00:53Z');
    expect(result.current.streaming).toBe(false);
    expect(latest().closed).toBe(true);
  });

  it('applies the completed event and notifies onTerminal once', () => {
    const onTerminal = vi.fn();
    const { result } = renderHook(() => useJobEvents(makeJob(), { factory, onTerminal }));

    act(() => {
      latest().emit('completed', {
        jobId: JOB_ID,
        status: 'SUCCEEDED',
        rowsProcessed: 10000000,
        durationMillis: 53640,
        artifact: {
          artifactId: 'a1',
          fileName: 'users.csv',
          sizeBytes: 2147483648,
          contentType: 'text/csv',
          kind: 'DATA',
        },
      });
    });

    expect(result.current.job?.status).toBe('SUCCEEDED');
    expect(result.current.job?.artifacts).toHaveLength(1);
    expect(result.current.streaming).toBe(false);
    expect(onTerminal).toHaveBeenCalledTimes(1);
    expect(onTerminal.mock.calls[0]?.[0]).toMatchObject({ status: 'SUCCEEDED' });
    expect(latest().closed).toBe(true);
  });

  it('accumulates log lines and bounds them', () => {
    const { result } = renderHook(() => useJobEvents(makeJob(), { factory, maxLogLines: 3 }));

    act(() => {
      for (let index = 0; index < 5; index += 1) {
        latest().emit('log', {
          jobId: JOB_ID,
          level: 'INFO',
          message: `line ${index}`,
          at: '2026-08-17T12:00:03Z',
        });
      }
    });

    expect(result.current.logs.map((line) => line.message)).toEqual(['line 2', 'line 3', 'line 4']);
  });

  it('defaults to a 500-line log window', () => {
    expect(DEFAULT_MAX_LOG_LINES).toBe(500);
  });

  it('surfaces a non-fatal job error event without ending the stream', () => {
    const { result } = renderHook(() => useJobEvents(makeJob(), { factory }));

    act(() => {
      latest().emit('error', {
        jobId: JOB_ID,
        problem: { type: 'about:blank', title: 'Row rejected', status: 422 },
        recoverable: true,
        failureCount: 3,
      });
    });

    expect(result.current.error).toMatchObject({ failureCount: 3 });
    expect(result.current.streaming).toBe(true);
  });

  it('surfaces a transport failure once the connection is definitively closed', () => {
    const { result } = renderHook(() => useJobEvents(makeJob(), { factory }));

    act(() => {
      const source = latest();
      source.readyState = 2;
      source.onerror?.();
    });

    expect(result.current.error).toBeTruthy();
    expect(result.current.streaming).toBe(false);
  });

  it('ignores a malformed payload rather than throwing', () => {
    const { result } = renderHook(() => useJobEvents(makeJob(), { factory }));
    act(() => {
      latest().emit('progress', 'not json');
    });
    expect(result.current.job?.progress).toBeUndefined();
  });

  it('does not reconnect when only the job snapshot identity changes', () => {
    const { rerender, result } = renderHook(
      ({ job }: { job: Job }) => useJobEvents(job, { factory }),
      { initialProps: { job: makeJob() } },
    );

    act(() => {
      latest().emit('progress', { jobId: JOB_ID, percent: 10 });
    });

    // A poll replaces the snapshot object; the deltas must still compose onto the fresh one.
    rerender({ job: makeJob({ name: 'Export demo.users (renamed)' }) });

    expect(FakeEventSource.instances).toHaveLength(1);
    expect(result.current.job?.name).toBe('Export demo.users (renamed)');
    expect(result.current.job?.progress?.percent).toBe(10);
  });

  it('resubscribes and drops stale deltas when the selected job changes', async () => {
    const other = 'aaaaaaaa-0000-0000-0000-000000000000';
    const { rerender, result } = renderHook(
      ({ job }: { job: Job }) => useJobEvents(job, { factory }),
      { initialProps: { job: makeJob() } },
    );

    act(() => {
      latest().emit('progress', { jobId: JOB_ID, percent: 10 });
      latest().emit('log', {
        jobId: JOB_ID,
        level: 'INFO',
        message: 'first job',
        at: '2026-08-17T12:00:03Z',
      });
    });

    rerender({ job: makeJob({ id: other }) });

    await waitFor(() => expect(result.current.logs).toHaveLength(0));
    expect(FakeEventSource.instances).toHaveLength(2);
    expect(FakeEventSource.instances[0]?.closed).toBe(true);
    expect(result.current.job?.progress).toBeUndefined();
  });

  it('reset() clears accumulated deltas, logs and errors', () => {
    const { result } = renderHook(() => useJobEvents(makeJob(), { factory }));

    act(() => {
      latest().emit('progress', { jobId: JOB_ID, percent: 10 });
      latest().emit('log', {
        jobId: JOB_ID,
        level: 'INFO',
        message: 'x',
        at: '2026-08-17T12:00:03Z',
      });
    });
    expect(result.current.logs).toHaveLength(1);

    act(() => result.current.reset());

    expect(result.current.logs).toHaveLength(0);
    expect(result.current.job?.progress).toBeUndefined();
    expect(result.current.error).toBeNull();
  });

  it('closes the stream on unmount', () => {
    const { unmount } = renderHook(() => useJobEvents(makeJob(), { factory }));
    const source = latest();
    unmount();
    expect(source.closed).toBe(true);
  });
});
