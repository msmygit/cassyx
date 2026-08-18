/**
 * Pure job-state and formatting helpers for the jobs UI (plan §5.5).
 *
 * No React, no I/O — everything here is a total function over contract types, so the interesting
 * behaviour (terminal-state guards, the work-stealing progress fallback, humanised readouts) is
 * unit-testable without rendering anything.
 */
import type {
  JobCompletedEvent,
  JobErrorEvent,
  JobLogEvent,
  JobProgressEvent,
  JobStatusEvent,
} from '../api/sse';
import { isTerminalStatus } from '../api/sse';
import type { Job, JobArtifact, JobProgress, JobStatus } from './jobsApi';

export type { JobCompletedEvent, JobErrorEvent, JobLogEvent, JobProgressEvent, JobStatusEvent };

/**
 * Re-exported rather than reimplemented: `api/sse.ts` already owns the terminal-state set and uses
 * it to auto-close the stream, so a second definition here could drift from the one that decides
 * when the socket closes.
 */
export function isTerminal(status: JobStatus): boolean {
  return isTerminalStatus(status);
}

/** `QUEUED`/`RUNNING` are the only cancellable states (`POST /cancel` 409s otherwise). */
export function canCancel(job: Pick<Job, 'status'>): boolean {
  return job.status === 'QUEUED' || job.status === 'RUNNING';
}

/** Only a finished job can be deleted; the contract 409s on a live one. */
export function canDelete(job: Pick<Job, 'status'>): boolean {
  return isTerminal(job.status);
}

/**
 * The primary artifact is the first `DATA` one, falling back to the first artifact of any kind.
 * `kind` is optional in the contract, so an entry without one is treated as data.
 */
export function primaryArtifact(job: Pick<Job, 'artifacts'>): JobArtifact | null {
  const artifacts = job.artifacts ?? [];
  return artifacts.find((a) => a.kind === undefined || a.kind === 'DATA') ?? artifacts[0] ?? null;
}

/** The download link only exists for a `SUCCEEDED` job that actually produced an artifact. */
export function canDownload(job: Pick<Job, 'status' | 'artifacts'>): boolean {
  return job.status === 'SUCCEEDED' && primaryArtifact(job) !== null;
}

export type JobStatusColor = 'default' | 'info' | 'success' | 'error' | 'warning';

/** MUI `Chip`/`LinearProgress` colour per lifecycle state. */
export function jobStatusColor(status: JobStatus): JobStatusColor {
  switch (status) {
    case 'QUEUED':
      return 'default';
    case 'RUNNING':
      return 'info';
    case 'SUCCEEDED':
      return 'success';
    case 'FAILED':
      return 'error';
    case 'CANCELLED':
      return 'warning';
    default:
      return 'default';
  }
}

/** True when at least one job is still live — drives the polling fallback interval. */
export function hasActiveJob(jobs: readonly Pick<Job, 'status'>[]): boolean {
  return jobs.some((job) => !isTerminal(job.status));
}

/* ---------------------------------------------------------------- progress computation */

const clampPercent = (value: number): number => Math.min(100, Math.max(0, value));

/**
 * Completion percentage, or `null` when it genuinely cannot be known yet (progress bar goes
 * indeterminate).
 *
 * `percent` is nullable in the contract because the native engine oversplits the token ring and
 * work-steals: until the row estimate lands, the only honest measure of progress is
 * `splitsCompleted / splitsTotal`. Falling back to it keeps the bar determinate for the whole
 * scan rather than only after the estimate arrives.
 */
export function progressPercent(job: Pick<Job, 'status' | 'progress'>): number | null {
  const progress = job.progress;
  if (job.status === 'SUCCEEDED') return 100;
  if (!progress) return null;
  if (typeof progress.percent === 'number' && Number.isFinite(progress.percent)) {
    return clampPercent(progress.percent);
  }
  const { splitsCompleted, splitsTotal } = progress;
  if (typeof splitsCompleted === 'number' && typeof splitsTotal === 'number' && splitsTotal > 0) {
    return clampPercent((splitsCompleted / splitsTotal) * 100);
  }
  const { rowsProcessed, totalRowsEstimate } = progress;
  if (
    typeof rowsProcessed === 'number' &&
    typeof totalRowsEstimate === 'number' &&
    totalRowsEstimate > 0
  ) {
    return clampPercent((rowsProcessed / totalRowsEstimate) * 100);
  }
  return null;
}

/* -------------------------------------------------------------------- event application */

/** Progress snapshot from the SSE `progress` event, merged onto the job's existing progress. */
export function mergeProgress(job: Job, event: JobProgressEvent): Job {
  const { jobId: _jobId, ...progress } = event;
  const merged: JobProgress = { ...job.progress, ...progress };
  return { ...job, progress: merged };
}

/**
 * Apply an SSE `status` event.
 *
 * Guards against a *downgrade*: the list query and the stream race, so a poll that already saw
 * `SUCCEEDED` must not be dragged back to `RUNNING` by a status event replayed from the buffer.
 */
export function applyStatusEvent(job: Job, event: JobStatusEvent): Job {
  if (isTerminal(job.status) && !isTerminal(event.status)) return job;
  const next: Job = { ...job, status: event.status };
  if (event.status === 'RUNNING' && !job.startedAt) next.startedAt = event.at;
  if (isTerminal(event.status) && !job.finishedAt) next.finishedAt = event.at;
  return next;
}

/** Apply the terminal SSE `completed` event: status, duration, artifacts, error, final rows. */
export function applyCompletedEvent(job: Job, event: JobCompletedEvent): Job {
  const artifacts =
    event.artifacts ?? (event.artifact ? [event.artifact] : (job.artifacts ?? undefined));
  const next: Job = { ...job, status: event.status };
  if (artifacts) next.artifacts = artifacts;
  if (event.durationMillis !== undefined) next.durationMillis = event.durationMillis;
  if (event.error !== undefined) next.error = event.error;
  if (event.rowsProcessed !== undefined) {
    next.progress = { ...job.progress, rowsProcessed: event.rowsProcessed };
  }
  return next;
}

/** Accumulated SSE deltas for one job, overlaid on the latest polled snapshot. */
export interface JobEventState {
  status?: JobStatusEvent;
  progress?: JobProgressEvent;
  completed?: JobCompletedEvent;
}

/**
 * Overlay the SSE deltas onto a job.
 *
 * Applied in lifecycle order so a `completed` event always wins over an earlier `status`, and the
 * result stays correct no matter how often the polled base snapshot is replaced underneath.
 */
export function applyJobEvents(job: Job, events: JobEventState): Job {
  let next = job;
  if (events.status) next = applyStatusEvent(next, events.status);
  if (events.progress) next = mergeProgress(next, events.progress);
  if (events.completed) next = applyCompletedEvent(next, events.completed);
  return next;
}

/** Append with a hard cap — a chatty DSBulk job can emit far more lines than a tab should hold. */
export function appendBounded<T>(items: readonly T[], item: T, max: number): T[] {
  if (max <= 0) return [];
  const next = [...items, item];
  return next.length > max ? next.slice(next.length - max) : next;
}

/* ------------------------------------------------------------------------- formatting */

const EM_DASH = '—';

function round(value: number, digits: number): string {
  return value.toFixed(digits).replace(/\.0+$/, '');
}

/**
 * Humanise a duration in ms: `—`, `4s`, `1m 30s`, `2h 05m`.
 * `null`/`undefined` is a legitimate contract value (no estimate yet), not an error.
 */
export function formatEta(millis: number | null | undefined): string {
  if (millis === null || millis === undefined || !Number.isFinite(millis) || millis < 0) {
    return EM_DASH;
  }
  const totalSeconds = Math.floor(millis / 1000);
  if (totalSeconds < 60) return `${totalSeconds}s`;
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  if (minutes < 60) return `${minutes}m ${String(seconds).padStart(2, '0')}s`;
  const hours = Math.floor(minutes / 60);
  return `${hours}h ${String(minutes % 60).padStart(2, '0')}m`;
}

/** Compact decimal count: `912`, `4.2K`, `1.9M`, `3.1B`. */
export function formatCount(value: number | null | undefined): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return EM_DASH;
  const abs = Math.abs(value);
  if (abs < 1000) return String(Math.round(value));
  const units = [
    { limit: 1e12, suffix: 'T' },
    { limit: 1e9, suffix: 'B' },
    { limit: 1e6, suffix: 'M' },
    { limit: 1e3, suffix: 'K' },
  ];
  for (const { limit, suffix } of units) {
    if (abs >= limit) return `${round(value / limit, 1)}${suffix}`;
  }
  return String(Math.round(value));
}

/** Throughput readout, e.g. `186.4K rows/s`. */
export function formatRowsPerSecond(value: number | null | undefined): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return EM_DASH;
  return `${formatCount(value)} rows/s`;
}

const BYTE_UNITS = ['B', 'KB', 'MB', 'GB', 'TB', 'PB'] as const;

/** Binary-prefix byte size, e.g. `859.5 MB`. */
export function formatBytes(value: number | null | undefined): string {
  if (value === null || value === undefined || !Number.isFinite(value) || value < 0) return EM_DASH;
  if (value < 1024) return `${Math.round(value)} B`;
  let size = value;
  let unit = 0;
  while (size >= 1024 && unit < BYTE_UNITS.length - 1) {
    size /= 1024;
    unit += 1;
  }
  return `${round(size, 1)} ${BYTE_UNITS[unit]}`;
}

/** Locale time for the list rows. Returns the raw string if it is not a parseable timestamp. */
export function formatTimestamp(value: string | null | undefined): string {
  if (!value) return EM_DASH;
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return value;
  return parsed.toLocaleString();
}

/** Display label for a job row: explicit name, else the target identity, else the job type. */
export function jobLabel(job: Job): string {
  if (job.name) return job.name;
  const identity = job.identity;
  if (identity?.keyspace && identity.table) return `${identity.keyspace}.${identity.table}`;
  if (identity?.keyspace) return identity.keyspace;
  return job.type;
}
