/**
 * Server-Sent Events helper for job progress streams (plan §5.5, `GET /api/jobs/{id}/events`).
 *
 * Two layers:
 *  - `parseSseChunk` / `SseParser` — a dependency-free, fully testable text/event-stream parser.
 *  - `subscribeToJobEvents` — an `EventSource`-backed subscription with typed JSON payloads.
 *
 * We keep our own parser because `EventSource` cannot send headers, so any future authenticated
 * stream has to run over `fetch` + `ReadableStream` instead — and both paths then share one parser.
 */
import { apiClient } from './client';
import { AppError, problemFromResponse, toAppError } from './errors';
import { publishLicenseRequired } from './licenseSignal';
import type { JobStatus, Schemas } from './types';

export type { JobStatus };

export interface SseMessage {
  /** Event name (`event:` field). Defaults to `message` per the spec. */
  event: string;
  /** Concatenated `data:` lines, newline-joined. */
  data: string;
  /** Last `id:` field seen, for `Last-Event-ID` resumption. */
  id?: string;
  /** `retry:` field in ms, if the server sent one. */
  retry?: number;
}

/**
 * Incremental text/event-stream parser.
 *
 * Feed it arbitrary chunks; it emits complete messages and buffers the remainder. Handles CRLF,
 * comment lines (`:` heartbeats), and multi-line `data:`.
 */
export class SseParser {
  private buffer = '';

  push(chunk: string): SseMessage[] {
    this.buffer += chunk.replace(/\r\n/g, '\n').replace(/\r/g, '\n');
    const messages: SseMessage[] = [];
    let boundary = this.buffer.indexOf('\n\n');
    while (boundary !== -1) {
      const raw = this.buffer.slice(0, boundary);
      this.buffer = this.buffer.slice(boundary + 2);
      const message = parseSseChunk(raw);
      if (message) messages.push(message);
      boundary = this.buffer.indexOf('\n\n');
    }
    return messages;
  }

  /** Bytes still buffered — non-empty means a partial message is in flight. */
  get pending(): string {
    return this.buffer;
  }
}

/** Parse one complete (already delimited) event block. Returns null for heartbeat-only blocks. */
export function parseSseChunk(raw: string): SseMessage | null {
  const dataLines: string[] = [];
  let event = 'message';
  let id: string | undefined;
  let retry: number | undefined;
  let sawField = false;

  for (const line of raw.split('\n')) {
    if (line === '' || line.startsWith(':')) continue; // comment / heartbeat
    const colon = line.indexOf(':');
    const field = colon === -1 ? line : line.slice(0, colon);
    let value = colon === -1 ? '' : line.slice(colon + 1);
    if (value.startsWith(' ')) value = value.slice(1);

    switch (field) {
      case 'event':
        event = value;
        sawField = true;
        break;
      case 'data':
        dataLines.push(value);
        sawField = true;
        break;
      case 'id':
        id = value;
        sawField = true;
        break;
      case 'retry': {
        const parsed = Number.parseInt(value, 10);
        if (!Number.isNaN(parsed)) retry = parsed;
        sawField = true;
        break;
      }
      default:
        break; // unknown fields are ignored per spec
    }
  }

  if (!sawField) return null;
  const message: SseMessage = { event, data: dataLines.join('\n') };
  if (id !== undefined) message.id = id;
  if (retry !== undefined) message.retry = retry;
  return message;
}

/**
 * Named event payloads carried on `GET /api/jobs/{jobId}/events`, straight from the contract.
 * The stream uses NAMED events (`status`, `progress`, `log`, `completed`, `error`), so a plain
 * `onmessage` handler would silently see nothing.
 */
export type JobStatusEvent = Schemas['JobStatusEvent'];
export type JobProgressEvent = Schemas['JobProgressEvent'];
export type JobLogEvent = Schemas['JobLogEvent'];
export type JobCompletedEvent = Schemas['JobCompletedEvent'];
export type JobErrorEvent = Schemas['JobErrorEvent'];

export interface SseSubscription {
  close: () => void;
}

export interface JobEventHandlers {
  onStatus?: (event: JobStatusEvent) => void;
  onProgress?: (event: JobProgressEvent) => void;
  onLog?: (event: JobLogEvent) => void;
  /** Terminal event — the server closes the stream right after it. */
  onComplete?: (event: JobCompletedEvent) => void;
  onJobError?: (event: JobErrorEvent) => void;
  /** Transport-level failure, as opposed to a job failure. */
  onError?: (error: AppError) => void;
  /**
   * The stream died because the licence gate refused it (plan §9.1). Arrives *after* `onError`,
   * because the reason can only be established with a second request (see `probeSseGate`).
   */
  onLicenseRequired?: (error: AppError) => void;
  /** Every raw event, including ones we do not model. */
  onMessage?: (message: SseMessage) => void;
}

/** Injectable so tests can supply a fake EventSource. */
export type EventSourceFactory = (url: string) => EventSource;

/**
 * How many `error` events we tolerate before closing the stream ourselves.
 *
 * Per the HTML spec a non-200 response fails the connection permanently, so a 402 *should* leave
 * `readyState === CLOSED` on the first error. Implementations and intermediaries disagree often
 * enough that a stream can end up retrying against the licence wall forever, which is a request
 * per few seconds for as long as the tab is open. This is the backstop.
 */
export const MAX_SSE_RECONNECT_ERRORS = 3;

/** Establishes *why* a stream died. Injectable so tests need no network. */
export type SseGateProbe = (url: string) => Promise<AppError | null>;

/**
 * Ask the stream URL once, over `fetch`, whether it is refusing us with a 402.
 *
 * `EventSource` exposes neither the status code nor the body of a failed response, so a licence
 * refusal is indistinguishable from a dropped connection at that layer. One bounded probe turns
 * "the job stream keeps dying" into the activation screen. Any non-402 answer (including the
 * stream actually opening again) is reported as "not a licence problem" and the response is
 * abandoned immediately rather than left streaming.
 */
export async function probeSseGate(
  url: string,
  fetchImpl: typeof fetch = globalThis.fetch,
): Promise<AppError | null> {
  const controller = new AbortController();
  try {
    const response = await fetchImpl(url, {
      headers: { Accept: 'text/event-stream' },
      credentials: 'same-origin',
      signal: controller.signal,
    });
    if (response.status !== 402) return null;
    return await problemFromResponse(response, 'SSE /api/jobs');
  } catch {
    // A failed probe means we simply do not know; the generic transport error already stands.
    return null;
  } finally {
    controller.abort();
  }
}

const TERMINAL: ReadonlySet<JobStatus> = new Set<JobStatus>(['SUCCEEDED', 'FAILED', 'CANCELLED']);

export function isTerminalStatus(status: JobStatus): boolean {
  return TERMINAL.has(status);
}

function safeParse<T>(data: string): T | null {
  try {
    return JSON.parse(data) as T;
  } catch {
    return null;
  }
}

/** Event names defined by the contract for the job stream. */
export const JOB_EVENT_NAMES = ['status', 'progress', 'log', 'completed', 'error'] as const;

/**
 * Subscribe to a job's SSE stream (plan §5.5).
 *
 * Auto-closes on `completed` and on a terminal `status`, so a finished job never leaves an open
 * connection behind.
 */
export function subscribeToJobEvents(
  jobId: string,
  handlers: JobEventHandlers,
  options: { factory?: EventSourceFactory; url?: string; probe?: SseGateProbe } = {},
): SseSubscription {
  const url = options.url ?? apiClient.url(`/api/jobs/${encodeURIComponent(jobId)}/events`);
  const factory: EventSourceFactory =
    options.factory ?? ((target: string) => new EventSource(target, { withCredentials: true }));

  let closed = false;
  const source = factory(url);

  const close = () => {
    if (closed) return;
    closed = true;
    source.close();
  };

  const handle = (name: string, raw: MessageEvent<string>) => {
    handlers.onMessage?.({ event: name, data: raw.data });
    switch (name) {
      case 'status': {
        const payload = safeParse<JobStatusEvent>(raw.data);
        if (!payload) return;
        handlers.onStatus?.(payload);
        if (isTerminalStatus(payload.status)) close();
        return;
      }
      case 'progress': {
        const payload = safeParse<JobProgressEvent>(raw.data);
        if (payload) handlers.onProgress?.(payload);
        return;
      }
      case 'log': {
        const payload = safeParse<JobLogEvent>(raw.data);
        if (payload) handlers.onLog?.(payload);
        return;
      }
      case 'completed': {
        const payload = safeParse<JobCompletedEvent>(raw.data);
        if (payload) handlers.onComplete?.(payload);
        close();
        return;
      }
      case 'error': {
        const payload = safeParse<JobErrorEvent>(raw.data);
        if (payload) handlers.onJobError?.(payload);
        return;
      }
      default:
        return;
    }
  };

  for (const name of JOB_EVENT_NAMES) {
    source.addEventListener(name, (event) => handle(name, event as MessageEvent<string>));
  }

  const probe: SseGateProbe = options.probe ?? ((target: string) => probeSseGate(target));
  let errorCount = 0;
  let probed = false;

  const askWhy = async () => {
    if (probed) return; // one probe per subscription: never trade a retry loop for a probe loop
    probed = true;
    const gateError = await probe(url);
    if (!gateError) return;
    // The app-wide reaction (re-check the licence, render the activation screen) is driven by the
    // signal; the handler is for the job pane's own messaging.
    publishLicenseRequired(gateError);
    handlers.onLicenseRequired?.(gateError);
  };

  source.onerror = () => {
    errorCount += 1;
    // EventSource reconnects on its own for transient drops; surface it but do not tear down
    // unless the connection is definitively closed - or unless it has failed so often that it is
    // clearly hitting a wall (a 402 from the licence gate looks exactly like this).
    const finished = source.readyState === 2 /* CLOSED */ || errorCount >= MAX_SSE_RECONNECT_ERRORS;
    if (!finished) return;

    handlers.onError?.(
      new AppError('Job progress stream closed', { kind: 'network', request: 'SSE /api/jobs' }),
    );
    close();
    void askWhy().catch(() => {}); // a failing probe is diagnostics, never a new failure mode
  };

  return { close };
}

/**
 * `fetch`-based streaming reader, for when headers are required (e.g. a license key header) and
 * `EventSource` therefore cannot be used.
 */
export async function readSseStream(
  response: Response,
  onMessage: (message: SseMessage) => void,
): Promise<void> {
  if (!response.body) {
    throw new AppError('Response has no readable body', { kind: 'parse', status: response.status });
  }
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  const parser = new SseParser();
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      for (const message of parser.push(decoder.decode(value, { stream: true }))) {
        onMessage(message);
      }
    }
  } catch (error) {
    throw toAppError(error, 'SSE stream');
  } finally {
    reader.releaseLock();
  }
}
