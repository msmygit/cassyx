import type { ReactNode } from 'react';
import { QueryClientProvider } from '@tanstack/react-query';
import { renderHook, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { createTestQueryClient } from '../test/render';
import type { Job, JobPage, JobsApi } from './jobsApi';
import {
  jobsQueryKeys,
  useCancelJob,
  useDeleteJob,
  useJobLogsQuery,
  useJobQuery,
  useJobsQuery,
} from './useJobs';

const JOB_ID = '6c8f2a10-b4f9-4a1e-9a12-5f0a7e2d3b44';

function makeJob(overrides: Partial<Job> = {}): Job {
  return {
    id: JOB_ID,
    name: 'Export demo.users',
    type: 'UNLOAD',
    status: 'SUCCEEDED',
    createdAt: '2026-08-17T12:00:00Z',
    ...overrides,
  };
}

function page(items: Job[]): JobPage {
  return { items, total: items.length, limit: 50, offset: 0 };
}

function stubApi(overrides: Partial<JobsApi> = {}): JobsApi {
  return {
    listJobs: vi.fn(async () => page([makeJob()])),
    getJob: vi.fn(async () => makeJob()),
    cancelJob: vi.fn(async () => makeJob({ status: 'CANCELLED' })),
    deleteJob: vi.fn(async () => undefined),
    fetchLogs: vi.fn(async () => ({ jobId: JOB_ID, lines: [] })),
    createUnloadJob: vi.fn(async () => makeJob({ status: 'QUEUED' })),
    artifactUrl: vi.fn(() => `/api/jobs/${JOB_ID}/artifact`),
    ...overrides,
  };
}

function wrapper() {
  const queryClient = createTestQueryClient();
  const Wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  );
  return { queryClient, Wrapper };
}

describe('jobsQueryKeys', () => {
  it('namespaces every key under the shared jobs root', () => {
    expect(jobsQueryKeys.all).toEqual(['jobs']);
    expect(jobsQueryKeys.list({ status: ['QUEUED'] })[0]).toBe('jobs');
    expect(jobsQueryKeys.detail(JOB_ID)).toEqual(['jobs', 'detail', JOB_ID]);
    expect(jobsQueryKeys.logs(JOB_ID, { tail: 10 })).toEqual([
      'jobs',
      'logs',
      JOB_ID,
      { tail: 10 },
    ]);
  });
});

describe('useJobsQuery', () => {
  it('fetches the page with the supplied filters', async () => {
    const api = stubApi();
    const { Wrapper } = wrapper();

    const { result } = renderHook(() => useJobsQuery({ status: ['RUNNING'] }, { api }), {
      wrapper: Wrapper,
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.items).toHaveLength(1);
    expect(api.listJobs).toHaveBeenCalledWith({ status: ['RUNNING'] });
  });

  it('does not fetch when disabled', () => {
    const api = stubApi();
    const { Wrapper } = wrapper();
    renderHook(() => useJobsQuery({}, { api, enabled: false }), { wrapper: Wrapper });
    expect(api.listJobs).not.toHaveBeenCalled();
  });

  it('polls only while a job is non-terminal', async () => {
    vi.useFakeTimers();
    try {
      const api = stubApi({ listJobs: vi.fn(async () => page([makeJob({ status: 'RUNNING' })])) });
      const { Wrapper } = wrapper();

      const { result } = renderHook(() => useJobsQuery({}, { api, pollIntervalMs: 1000 }), {
        wrapper: Wrapper,
      });

      await vi.waitFor(() => expect(result.current.isSuccess).toBe(true));
      const afterFirst = (api.listJobs as ReturnType<typeof vi.fn>).mock.calls.length;

      await vi.advanceTimersByTimeAsync(2500);
      expect((api.listJobs as ReturnType<typeof vi.fn>).mock.calls.length).toBeGreaterThan(
        afterFirst,
      );
    } finally {
      vi.useRealTimers();
    }
  });

  it('stops polling once every job is terminal', async () => {
    vi.useFakeTimers();
    try {
      const api = stubApi();
      const { Wrapper } = wrapper();

      const { result } = renderHook(() => useJobsQuery({}, { api, pollIntervalMs: 1000 }), {
        wrapper: Wrapper,
      });

      await vi.waitFor(() => expect(result.current.isSuccess).toBe(true));
      const afterFirst = (api.listJobs as ReturnType<typeof vi.fn>).mock.calls.length;

      await vi.advanceTimersByTimeAsync(5000);
      expect((api.listJobs as ReturnType<typeof vi.fn>).mock.calls.length).toBe(afterFirst);
    } finally {
      vi.useRealTimers();
    }
  });

  it('honours pollIntervalMs: 0 as "never poll"', async () => {
    vi.useFakeTimers();
    try {
      const api = stubApi({ listJobs: vi.fn(async () => page([makeJob({ status: 'RUNNING' })])) });
      const { Wrapper } = wrapper();

      const { result } = renderHook(() => useJobsQuery({}, { api, pollIntervalMs: 0 }), {
        wrapper: Wrapper,
      });

      await vi.waitFor(() => expect(result.current.isSuccess).toBe(true));
      const afterFirst = (api.listJobs as ReturnType<typeof vi.fn>).mock.calls.length;

      await vi.advanceTimersByTimeAsync(10000);
      expect((api.listJobs as ReturnType<typeof vi.fn>).mock.calls.length).toBe(afterFirst);
    } finally {
      vi.useRealTimers();
    }
  });
});

describe('useJobQuery / useJobLogsQuery', () => {
  it('stays idle without a job id', () => {
    const api = stubApi();
    const { Wrapper } = wrapper();
    renderHook(() => useJobQuery(null, { api }), { wrapper: Wrapper });
    renderHook(() => useJobLogsQuery(null, {}, { api }), { wrapper: Wrapper });
    expect(api.getJob).not.toHaveBeenCalled();
    expect(api.fetchLogs).not.toHaveBeenCalled();
  });

  it('fetches one job and its retained logs', async () => {
    const api = stubApi();
    const { Wrapper } = wrapper();

    const job = renderHook(() => useJobQuery(JOB_ID, { api, pollIntervalMs: 0 }), {
      wrapper: Wrapper,
    });
    const logs = renderHook(() => useJobLogsQuery(JOB_ID, { tail: 100 }, { api }), {
      wrapper: Wrapper,
    });

    await waitFor(() => expect(job.result.current.isSuccess).toBe(true));
    await waitFor(() => expect(logs.result.current.isSuccess).toBe(true));
    expect(api.getJob).toHaveBeenCalledWith(JOB_ID);
    expect(api.fetchLogs).toHaveBeenCalledWith(JOB_ID, { tail: 100 });
  });
});

describe('useCancelJob / useDeleteJob', () => {
  it('cancels and invalidates every job query', async () => {
    const api = stubApi();
    const { queryClient, Wrapper } = wrapper();
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries');

    const { result } = renderHook(() => useCancelJob({ api }), { wrapper: Wrapper });
    result.current.mutate(JOB_ID);

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(api.cancelJob).toHaveBeenCalledWith(JOB_ID);
    expect(invalidate).toHaveBeenCalledWith({ queryKey: jobsQueryKeys.all });
  });

  it('deletes and invalidates every job query', async () => {
    const api = stubApi();
    const { queryClient, Wrapper } = wrapper();
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries');

    const { result } = renderHook(() => useDeleteJob({ api }), { wrapper: Wrapper });
    result.current.mutate(JOB_ID);

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(api.deleteJob).toHaveBeenCalledWith(JOB_ID);
    expect(invalidate).toHaveBeenCalledWith({ queryKey: jobsQueryKeys.all });
  });

  it('surfaces a failed cancellation', async () => {
    const api = stubApi({
      cancelJob: vi.fn(async () => {
        throw new Error('already terminal');
      }),
    });
    const { Wrapper } = wrapper();

    const { result } = renderHook(() => useCancelJob({ api }), { wrapper: Wrapper });
    result.current.mutate(JOB_ID);

    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(result.current.error?.message).toBe('already terminal');
  });
});
