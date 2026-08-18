/**
 * TanStack Query hooks for the job substrate (plan §5.5).
 *
 * SSE is the primary progress channel; these queries are the *fallback* and the source of truth
 * for the list itself. The refetch interval is therefore conditional: it only ticks while at least
 * one job is non-terminal, so an idle Jobs panel makes no requests at all.
 *
 * Query keys are namespaced under the shell's `queryKeys.jobs` (`src/api/queryClient.ts`), so a
 * mutation here invalidates every job query regardless of its filters.
 */
import {
  useMutation,
  useQuery,
  useQueryClient,
  type UseMutationResult,
  type UseQueryResult,
} from '@tanstack/react-query';
import {
  defaultJobsApi,
  type Job,
  type JobFilters,
  type JobLogOptions,
  type JobLogPage,
  type JobPage,
  type JobsApi,
} from './jobsApi';
import { hasActiveJob } from './jobsModel';

/** Matches the SSE `progress` cadence (~1/s) without hammering the API when SSE is working. */
export const DEFAULT_JOB_POLL_MS = 3000;

export const jobsQueryKeys = {
  all: ['jobs'] as const,
  list: (filters: JobFilters) => ['jobs', 'list', filters] as const,
  detail: (jobId: string) => ['jobs', 'detail', jobId] as const,
  logs: (jobId: string, options: JobLogOptions) => ['jobs', 'logs', jobId, options] as const,
};

export interface JobsHookOptions {
  api?: JobsApi;
  enabled?: boolean;
}

export interface JobsQueryOptions extends JobsHookOptions {
  /** Polling fallback interval while any job is live. `0` disables polling entirely. */
  pollIntervalMs?: number;
}

export function useJobsQuery(
  filters: JobFilters = {},
  options: JobsQueryOptions = {},
): UseQueryResult<JobPage> {
  const { api = defaultJobsApi, enabled = true, pollIntervalMs = DEFAULT_JOB_POLL_MS } = options;

  return useQuery({
    queryKey: jobsQueryKeys.list(filters),
    queryFn: () => api.listJobs(filters),
    enabled,
    // Poll only while something is actually moving — a settled list is static.
    refetchInterval: (query) => {
      if (pollIntervalMs <= 0) return false;
      const page = query.state.data;
      return page && hasActiveJob(page.items) ? pollIntervalMs : false;
    },
  });
}

export function useJobQuery(
  jobId: string | null | undefined,
  options: JobsQueryOptions = {},
): UseQueryResult<Job> {
  const { api = defaultJobsApi, enabled = true, pollIntervalMs = DEFAULT_JOB_POLL_MS } = options;

  return useQuery({
    queryKey: jobsQueryKeys.detail(jobId ?? ''),
    queryFn: () => api.getJob(jobId as string),
    enabled: enabled && Boolean(jobId),
    refetchInterval: (query) => {
      if (pollIntervalMs <= 0) return false;
      const job = query.state.data;
      return job && hasActiveJob([job]) ? pollIntervalMs : false;
    },
  });
}

export function useJobLogsQuery(
  jobId: string | null | undefined,
  logOptions: JobLogOptions = {},
  options: JobsHookOptions = {},
): UseQueryResult<JobLogPage> {
  const { api = defaultJobsApi, enabled = true } = options;

  return useQuery({
    queryKey: jobsQueryKeys.logs(jobId ?? '', logOptions),
    queryFn: () => api.fetchLogs(jobId as string, logOptions),
    enabled: enabled && Boolean(jobId),
  });
}

export function useCancelJob(options: JobsHookOptions = {}): UseMutationResult<Job, Error, string> {
  const { api = defaultJobsApi } = options;
  const queryClient = useQueryClient();

  return useMutation<Job, Error, string>({
    mutationFn: (jobId) => api.cancelJob(jobId),
    // Cancellation is asynchronous — the row stays `RUNNING` until the server publishes
    // `CANCELLED`, so refetch rather than writing an optimistic terminal state.
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: jobsQueryKeys.all });
    },
  });
}

export function useDeleteJob(
  options: JobsHookOptions = {},
): UseMutationResult<void, Error, string> {
  const { api = defaultJobsApi } = options;
  const queryClient = useQueryClient();

  return useMutation<void, Error, string>({
    mutationFn: (jobId) => api.deleteJob(jobId),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: jobsQueryKeys.all });
    },
  });
}
