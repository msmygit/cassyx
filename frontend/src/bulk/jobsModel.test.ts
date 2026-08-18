import { describe, expect, it } from 'vitest';
import type { Job, JobArtifact, JobStatus } from './jobsApi';
import {
  appendBounded,
  applyCompletedEvent,
  applyJobEvents,
  applyStatusEvent,
  canCancel,
  canDelete,
  canDownload,
  formatBytes,
  formatCount,
  formatEta,
  formatRowsPerSecond,
  formatTimestamp,
  hasActiveJob,
  isTerminal,
  jobLabel,
  jobStatusColor,
  mergeProgress,
  primaryArtifact,
  progressPercent,
  type JobCompletedEvent,
  type JobProgressEvent,
  type JobStatusEvent,
} from './jobsModel';

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

const ARTIFACT: JobArtifact = {
  artifactId: 'a1',
  fileName: 'users.csv',
  sizeBytes: 2147483648,
  contentType: 'text/csv',
  kind: 'DATA',
};

const ALL_STATUSES: JobStatus[] = ['QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED'];

describe('isTerminal', () => {
  it('classifies every contract status', () => {
    expect(ALL_STATUSES.map(isTerminal)).toEqual([false, false, true, true, true]);
  });
});

describe('canCancel / canDelete', () => {
  it('allows cancel only while queued or running', () => {
    expect(ALL_STATUSES.map((status) => canCancel({ status }))).toEqual([
      true,
      true,
      false,
      false,
      false,
    ]);
  });

  it('allows delete only once terminal — the API 409s on a live job', () => {
    expect(ALL_STATUSES.map((status) => canDelete({ status }))).toEqual([
      false,
      false,
      true,
      true,
      true,
    ]);
  });
});

describe('primaryArtifact / canDownload', () => {
  it('returns null when there are no artifacts', () => {
    expect(primaryArtifact({})).toBeNull();
    expect(primaryArtifact({ artifacts: [] })).toBeNull();
  });

  it('prefers the DATA artifact over log and report artifacts', () => {
    const artifacts: JobArtifact[] = [
      { ...ARTIFACT, artifactId: 'l1', fileName: 'operation.log', kind: 'LOG' },
      ARTIFACT,
    ];
    expect(primaryArtifact({ artifacts })?.artifactId).toBe('a1');
  });

  it('treats an artifact with no kind as data', () => {
    const untyped: JobArtifact = { ...ARTIFACT, kind: undefined };
    expect(primaryArtifact({ artifacts: [untyped] })?.artifactId).toBe('a1');
  });

  it('falls back to the first artifact when none is data-like', () => {
    const artifacts: JobArtifact[] = [{ ...ARTIFACT, artifactId: 'h1', kind: 'HOCON' }];
    expect(primaryArtifact({ artifacts })?.artifactId).toBe('h1');
  });

  it('offers a download only for a SUCCEEDED job that produced an artifact', () => {
    expect(canDownload({ status: 'SUCCEEDED', artifacts: [ARTIFACT] })).toBe(true);
    expect(canDownload({ status: 'SUCCEEDED', artifacts: [] })).toBe(false);
    expect(canDownload({ status: 'RUNNING', artifacts: [ARTIFACT] })).toBe(false);
    expect(canDownload({ status: 'FAILED', artifacts: [ARTIFACT] })).toBe(false);
  });
});

describe('jobStatusColor', () => {
  it('maps every status to a chip colour', () => {
    expect(ALL_STATUSES.map(jobStatusColor)).toEqual([
      'default',
      'info',
      'success',
      'error',
      'warning',
    ]);
  });

  it('falls back to default for an unknown status from a newer server', () => {
    expect(jobStatusColor('PAUSED' as JobStatus)).toBe('default');
  });
});

describe('hasActiveJob', () => {
  it('is true when any job is not terminal', () => {
    expect(hasActiveJob([])).toBe(false);
    expect(hasActiveJob([{ status: 'SUCCEEDED' }, { status: 'FAILED' }])).toBe(false);
    expect(hasActiveJob([{ status: 'SUCCEEDED' }, { status: 'QUEUED' }])).toBe(true);
  });
});

describe('progressPercent', () => {
  it('uses percent when the server supplies one', () => {
    expect(progressPercent(makeJob({ progress: { percent: 42.1 } }))).toBeCloseTo(42.1);
  });

  it('clamps a percent outside 0..100', () => {
    expect(progressPercent(makeJob({ progress: { percent: 140 } }))).toBe(100);
    expect(progressPercent(makeJob({ progress: { percent: -3 } }))).toBe(0);
  });

  it('falls back to the native engine work-stealing split counter when percent is null', () => {
    const job = makeJob({ progress: { percent: null, splitsCompleted: 4103, splitsTotal: 10000 } });
    expect(progressPercent(job)).toBeCloseTo(41.03);
  });

  it('falls back to rows when neither percent nor splits are usable', () => {
    const job = makeJob({
      progress: { percent: null, splitsTotal: 0, rowsProcessed: 25, totalRowsEstimate: 100 },
    });
    expect(progressPercent(job)).toBe(25);
  });

  it('is null (indeterminate) when nothing is known yet', () => {
    expect(progressPercent(makeJob())).toBeNull();
    expect(progressPercent(makeJob({ progress: {} }))).toBeNull();
    expect(progressPercent(makeJob({ progress: { percent: null, rowsProcessed: 10 } }))).toBeNull();
    expect(progressPercent(makeJob({ progress: { percent: Number.NaN } }))).toBeNull();
  });

  it('reports 100 for a succeeded job even without a progress payload', () => {
    expect(progressPercent(makeJob({ status: 'SUCCEEDED' }))).toBe(100);
  });
});

describe('mergeProgress', () => {
  const event: JobProgressEvent = {
    jobId: JOB_ID,
    rowsProcessed: 4210000,
    percent: 42.1,
    rowsPerSecond: 186420,
  };

  it('applies the snapshot without leaking jobId into JobProgress', () => {
    const next = mergeProgress(makeJob(), event);
    expect(next.progress).toEqual({
      rowsProcessed: 4210000,
      percent: 42.1,
      rowsPerSecond: 186420,
    });
    expect(next.progress as Record<string, unknown>).not.toHaveProperty('jobId');
  });

  it('keeps fields the newer snapshot omitted', () => {
    const job = makeJob({ progress: { splitsTotal: 10000, currentPhase: 'UNLOADING' } });
    expect(mergeProgress(job, event).progress).toMatchObject({
      splitsTotal: 10000,
      currentPhase: 'UNLOADING',
      percent: 42.1,
    });
  });

  it('returns a new object rather than mutating the input', () => {
    const job = makeJob({ progress: { percent: 1 } });
    const next = mergeProgress(job, event);
    expect(next).not.toBe(job);
    expect(job.progress?.percent).toBe(1);
  });
});

describe('applyStatusEvent', () => {
  const started: JobStatusEvent = {
    jobId: JOB_ID,
    status: 'RUNNING',
    at: '2026-08-17T12:00:03Z',
  };

  it('records startedAt on the first transition to RUNNING', () => {
    const next = applyStatusEvent(makeJob({ status: 'QUEUED' }), started);
    expect(next.status).toBe('RUNNING');
    expect(next.startedAt).toBe('2026-08-17T12:00:03Z');
  });

  it('does not overwrite an existing startedAt', () => {
    const job = makeJob({ status: 'QUEUED', startedAt: '2026-08-17T11:59:00Z' });
    expect(applyStatusEvent(job, started).startedAt).toBe('2026-08-17T11:59:00Z');
  });

  it('records finishedAt on a terminal transition', () => {
    const next = applyStatusEvent(makeJob(), {
      jobId: JOB_ID,
      status: 'CANCELLED',
      at: '2026-08-17T12:00:53Z',
    });
    expect(next.status).toBe('CANCELLED');
    expect(next.finishedAt).toBe('2026-08-17T12:00:53Z');
  });

  it('refuses to downgrade a job that already reached a terminal state', () => {
    const job = makeJob({ status: 'SUCCEEDED' });
    expect(applyStatusEvent(job, started)).toBe(job);
  });

  it('still allows one terminal state to correct another', () => {
    const job = makeJob({ status: 'CANCELLED' });
    const next = applyStatusEvent(job, { jobId: JOB_ID, status: 'FAILED', at: 'x' });
    expect(next.status).toBe('FAILED');
  });
});

describe('applyCompletedEvent', () => {
  const completed: JobCompletedEvent = {
    jobId: JOB_ID,
    status: 'SUCCEEDED',
    rowsProcessed: 10000000,
    durationMillis: 53640,
    artifact: ARTIFACT,
  };

  it('applies status, duration, artifact and final row count', () => {
    const next = applyCompletedEvent(makeJob({ progress: { percent: 99 } }), completed);
    expect(next.status).toBe('SUCCEEDED');
    expect(next.durationMillis).toBe(53640);
    expect(next.artifacts).toEqual([ARTIFACT]);
    expect(next.progress).toEqual({ percent: 99, rowsProcessed: 10000000 });
  });

  it('prefers the artifacts array when the server sends several', () => {
    const second: JobArtifact = { ...ARTIFACT, artifactId: 'a2', fileName: 'users-2.csv' };
    const next = applyCompletedEvent(makeJob(), { ...completed, artifacts: [ARTIFACT, second] });
    expect(next.artifacts).toHaveLength(2);
  });

  it('keeps existing artifacts when the event carries none', () => {
    const job = makeJob({ artifacts: [ARTIFACT] });
    const next = applyCompletedEvent(job, { jobId: JOB_ID, status: 'SUCCEEDED' });
    expect(next.artifacts).toEqual([ARTIFACT]);
    expect(next.durationMillis).toBeUndefined();
    expect(next.progress).toBeUndefined();
  });

  it('carries a failure problem through', () => {
    const next = applyCompletedEvent(makeJob(), {
      jobId: JOB_ID,
      status: 'FAILED',
      error: { type: 'about:blank', title: 'Write failed', status: 500 },
    });
    expect(next.status).toBe('FAILED');
    expect(next.error?.title).toBe('Write failed');
  });
});

describe('applyJobEvents', () => {
  it('is the identity when no events have arrived', () => {
    const job = makeJob();
    expect(applyJobEvents(job, {})).toBe(job);
  });

  it('applies status, then progress, then completed', () => {
    const job = makeJob({ status: 'QUEUED' });
    const next = applyJobEvents(job, {
      status: { jobId: JOB_ID, status: 'RUNNING', at: '2026-08-17T12:00:03Z' },
      progress: { jobId: JOB_ID, percent: 42.1, rowsPerSecond: 186420 },
      completed: { jobId: JOB_ID, status: 'SUCCEEDED', rowsProcessed: 10, artifact: ARTIFACT },
    });
    expect(next.status).toBe('SUCCEEDED');
    expect(next.startedAt).toBe('2026-08-17T12:00:03Z');
    expect(next.progress).toMatchObject({ percent: 42.1, rowsProcessed: 10 });
    expect(next.artifacts).toEqual([ARTIFACT]);
  });

  it('does not let a replayed RUNNING status drag a settled snapshot backwards', () => {
    const polled = makeJob({ status: 'SUCCEEDED' });
    const next = applyJobEvents(polled, {
      status: { jobId: JOB_ID, status: 'RUNNING', at: '2026-08-17T12:00:03Z' },
    });
    expect(next.status).toBe('SUCCEEDED');
  });
});

describe('appendBounded', () => {
  it('appends below the cap', () => {
    expect(appendBounded([1, 2], 3, 5)).toEqual([1, 2, 3]);
  });

  it('drops the oldest entries once the cap is reached', () => {
    expect(appendBounded([1, 2, 3], 4, 3)).toEqual([2, 3, 4]);
  });

  it('returns empty for a non-positive cap', () => {
    expect(appendBounded([1, 2], 3, 0)).toEqual([]);
  });

  it('never mutates the input', () => {
    const items = [1, 2];
    appendBounded(items, 3, 2);
    expect(items).toEqual([1, 2]);
  });
});

describe('formatEta', () => {
  it('renders an em dash when there is no estimate', () => {
    expect(formatEta(null)).toBe('—');
    expect(formatEta(undefined)).toBe('—');
    expect(formatEta(Number.NaN)).toBe('—');
    expect(formatEta(-1)).toBe('—');
  });

  it('renders seconds, minutes and hours', () => {
    expect(formatEta(0)).toBe('0s');
    expect(formatEta(31100)).toBe('31s');
    expect(formatEta(90000)).toBe('1m 30s');
    expect(formatEta(65000)).toBe('1m 05s');
    expect(formatEta(7500000)).toBe('2h 05m');
  });
});

describe('formatCount / formatRowsPerSecond', () => {
  it('formats compact decimal counts', () => {
    expect(formatCount(0)).toBe('0');
    expect(formatCount(912)).toBe('912');
    expect(formatCount(4210)).toBe('4.2K');
    expect(formatCount(4000)).toBe('4K');
    expect(formatCount(10000000)).toBe('10M');
    expect(formatCount(3.1e9)).toBe('3.1B');
    expect(formatCount(2.5e12)).toBe('2.5T');
  });

  it('renders an em dash for a missing count', () => {
    expect(formatCount(null)).toBe('—');
    expect(formatCount(undefined)).toBe('—');
    expect(formatCount(Number.POSITIVE_INFINITY)).toBe('—');
  });

  it('labels throughput', () => {
    expect(formatRowsPerSecond(186420)).toBe('186.4K rows/s');
    expect(formatRowsPerSecond(null)).toBe('—');
  });
});

describe('formatBytes', () => {
  it('uses binary prefixes', () => {
    expect(formatBytes(0)).toBe('0 B');
    expect(formatBytes(512)).toBe('512 B');
    expect(formatBytes(1024)).toBe('1 KB');
    expect(formatBytes(901234567)).toBe('859.5 MB');
    expect(formatBytes(2147483648)).toBe('2 GB');
    expect(formatBytes(1.5 * 1024 ** 5)).toBe('1.5 PB');
  });

  it('renders an em dash for a missing or negative size', () => {
    expect(formatBytes(null)).toBe('—');
    expect(formatBytes(undefined)).toBe('—');
    expect(formatBytes(-5)).toBe('—');
  });
});

describe('formatTimestamp', () => {
  it('renders an em dash for a missing value', () => {
    expect(formatTimestamp(null)).toBe('—');
    expect(formatTimestamp('')).toBe('—');
  });

  it('returns the raw string when it is not parseable', () => {
    expect(formatTimestamp('not-a-date')).toBe('not-a-date');
  });

  it('renders a parseable timestamp as a locale string', () => {
    expect(formatTimestamp('2026-08-17T12:00:00Z')).toBe(
      new Date('2026-08-17T12:00:00Z').toLocaleString(),
    );
  });
});

describe('jobLabel', () => {
  it('prefers the explicit name', () => {
    expect(jobLabel(makeJob())).toBe('Export demo.users');
  });

  it('falls back to the qualified identity', () => {
    const job = makeJob({
      name: undefined,
      identity: { kind: 'TABLE', keyspace: 'demo', table: 'users' },
    });
    expect(jobLabel(job)).toBe('demo.users');
  });

  it('falls back to the keyspace alone, then to the job type', () => {
    expect(
      jobLabel(makeJob({ name: undefined, identity: { kind: 'KEYSPACE', keyspace: 'demo' } })),
    ).toBe('demo');
    expect(jobLabel(makeJob({ name: undefined }))).toBe('UNLOAD');
  });
});
