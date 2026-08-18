import { QueryClient } from '@tanstack/react-query';
import { AppError } from './errors';

/**
 * Shared TanStack Query configuration.
 *
 * Retry policy is driven by `AppError.isRetryable`, so a 402 (license required) or a 404 fails
 * fast instead of hammering the API three times before showing the activation screen.
 */
export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 30_000,
        gcTime: 5 * 60_000,
        refetchOnWindowFocus: false,
        retry: (failureCount, error) => {
          if (error instanceof AppError && !error.isRetryable) return false;
          return failureCount < 2;
        },
        retryDelay: (attempt) => Math.min(1000 * 2 ** attempt, 8000),
      },
      mutations: {
        retry: false,
      },
    },
  });
}

/** Query key factory — keeps cache keys consistent across workstreams. */
export const queryKeys = {
  license: ['license'] as const,
  connections: ['connections'] as const,
  sessions: ['sessions'] as const,
  astraDatabases: (tokenFingerprint: string) => ['astra', 'databases', tokenFingerprint] as const,
  astraBundles: (databaseId: string) => ['astra', 'bundles', databaseId] as const,
  schema: (connectionId: string) => ['schema', connectionId] as const,
  jobs: ['jobs'] as const,
};
