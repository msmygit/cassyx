/**
 * Live job state from the SSE stream (`GET /api/jobs/{id}/events`, plan §5.5).
 *
 * Wraps `api/sse.ts`'s `subscribeToJobEvents` — which is owned by another workstream and is the
 * ONLY thing that should ever construct the subscription. It matters here because the contract
 * emits NAMED events (`status`, `progress`, `log`, `completed`, `error`); an `EventSource` with a
 * plain `onmessage` handler receives literally nothing, and the bug looks like "the server never
 * sends progress". Every consumer goes through that helper so nobody rediscovers this.
 *
 * State model: the hook stores only the *deltas* it has seen and overlays them on the caller's
 * latest snapshot via `applyJobEvents`. The list query keeps refetching underneath, so holding a
 * private copy of the job would go stale; holding only deltas lets both sources compose.
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { subscribeToJobEvents, type EventSourceFactory } from '../api/sse';
import type { AppError } from '../api/errors';
import type { Job } from './jobsApi';
import {
  appendBounded,
  applyJobEvents,
  isTerminal,
  type JobCompletedEvent,
  type JobErrorEvent,
  type JobEventState,
  type JobLogEvent,
} from './jobsModel';

/** A busy DSBulk job can emit thousands of lines; the log pane keeps a bounded tail. */
export const DEFAULT_MAX_LOG_LINES = 500;

export interface UseJobEventsOptions {
  /** `false` suspends the subscription (e.g. the job is already terminal, or nothing selected). */
  enabled?: boolean;
  /** Injectable `EventSource` constructor, so tests can drive named events synchronously. */
  factory?: EventSourceFactory;
  /** Override the stream URL — use `job.eventsUrl` when the server supplies one. */
  url?: string;
  maxLogLines?: number;
  /** Called once when a terminal event arrives, so the caller can invalidate its list query. */
  onTerminal?: (job: Job) => void;
}

export interface UseJobEventsResult {
  /** The caller's job with every SSE delta applied, or `null` when no job is selected. */
  job: Job | null;
  logs: JobLogEvent[];
  /** Transport failure, or a non-fatal `error` event published by the job itself. */
  error: AppError | JobErrorEvent | null;
  /** `true` while a subscription is open. */
  streaming: boolean;
  /** Drop accumulated deltas and logs — used when switching jobs. */
  reset: () => void;
}

/**
 * Subscribe to one job's progress stream.
 *
 * @param job the latest snapshot from the list/detail query. Identity may change on every poll;
 *   only `job.id` is used as a subscription key, so refetches never reconnect the stream.
 */
export function useJobEvents(
  job: Job | null | undefined,
  options: UseJobEventsOptions = {},
): UseJobEventsResult {
  const { enabled = true, factory, url, maxLogLines = DEFAULT_MAX_LOG_LINES, onTerminal } = options;

  const jobId = job?.id ?? null;
  const [events, setEvents] = useState<JobEventState>({});
  const [logs, setLogs] = useState<JobLogEvent[]>([]);
  const [error, setError] = useState<AppError | JobErrorEvent | null>(null);
  const [streaming, setStreaming] = useState(false);

  // Kept in refs so a new inline callback from the parent does not tear down the stream.
  const onTerminalRef = useRef(onTerminal);
  const jobRef = useRef<Job | null>(job ?? null);
  useEffect(() => {
    onTerminalRef.current = onTerminal;
    jobRef.current = job ?? null;
  });

  const reset = useCallback(() => {
    setEvents({});
    setLogs([]);
    setError(null);
  }, []);

  // Deltas belong to one job id; carrying them across a selection change would corrupt the next.
  useEffect(() => {
    setEvents({});
    setLogs([]);
    setError(null);
  }, [jobId]);

  useEffect(() => {
    if (!jobId || !enabled) {
      setStreaming(false);
      return;
    }

    let active = true;
    setStreaming(true);

    const finish = (completed: JobCompletedEvent) => {
      setEvents((previous) => ({ ...previous, completed }));
      const base = jobRef.current;
      if (base) onTerminalRef.current?.(applyJobEvents(base, { completed }));
    };

    const subscription = subscribeToJobEvents(
      jobId,
      {
        onStatus: (event) => {
          if (!active) return;
          setEvents((previous) => ({ ...previous, status: event }));
          // `subscribeToJobEvents` closes the socket itself on a terminal status.
          if (isTerminal(event.status)) setStreaming(false);
        },
        onProgress: (event) => {
          if (active) setEvents((previous) => ({ ...previous, progress: event }));
        },
        onLog: (event) => {
          if (active) setLogs((previous) => appendBounded(previous, event, maxLogLines));
        },
        onComplete: (event) => {
          if (!active) return;
          finish(event);
          setStreaming(false);
        },
        onJobError: (event) => {
          if (active) setError(event);
        },
        onError: (transportError) => {
          if (!active) return;
          setError(transportError);
          setStreaming(false);
        },
      },
      { factory, url },
    );

    return () => {
      active = false;
      setStreaming(false);
      subscription.close();
    };
  }, [jobId, enabled, maxLogLines, factory, url]);

  const merged = useMemo(() => (job ? applyJobEvents(job, events) : null), [job, events]);

  return { job: merged, logs, error, streaming, reset };
}
