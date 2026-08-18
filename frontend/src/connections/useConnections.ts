/**
 * TanStack Query bindings for the connections API (plan §3).
 *
 * Cache invalidation is the whole point: connecting or disconnecting changes `ConnectionResponse
 * .connected`, which the sidebar renders, so both mutations invalidate the connection list as well
 * as the session list. Getting that wrong shows a stale "disconnected" badge on a live session and
 * makes the product feel broken in the most visible place it has.
 */
import {
  useMutation,
  useQuery,
  useQueryClient,
  type UseMutationResult,
  type UseQueryResult,
} from '@tanstack/react-query';
import { queryKeys } from '../api/queryClient';
import {
  connectConnection,
  deleteConnection,
  disconnectConnection,
  getClusterCapabilities,
  getConnectionHealth,
  listConnections,
  testConnection,
  type ClusterCapabilities,
  type ConnectionHealth,
  type ConnectionResponse,
  type ConnectionTestResult,
  type SessionState,
} from './connectionsApi';
import { saveConnection, type SaveConnectionOptions, type SaveConnectionResult } from './saveConnection';

export function useConnections(): UseQueryResult<ConnectionResponse[]> {
  return useQuery({ queryKey: queryKeys.connections, queryFn: () => listConnections() });
}

export function useConnectionHealth(
  connectionId: string | undefined,
  enabled = true,
): UseQueryResult<ConnectionHealth> {
  return useQuery({
    queryKey: ['connection-health', connectionId],
    queryFn: () => getConnectionHealth(connectionId as string),
    enabled: Boolean(connectionId) && enabled,
    // The indicator should feel live without hammering the API; the endpoint is cheap by design.
    refetchInterval: 15_000,
  });
}

export function useClusterCapabilities(
  connectionId: string | undefined,
): UseQueryResult<ClusterCapabilities> {
  return useQuery({
    queryKey: ['capabilities', connectionId],
    queryFn: () => getClusterCapabilities(connectionId as string),
    enabled: Boolean(connectionId),
    // Capabilities are fixed for the life of a session; re-probing on every mount is pure noise.
    staleTime: Infinity,
  });
}

/** Invalidates both lists, because `connected` lives on the connection as well as the session. */
function useSessionMutation(
  action: (connectionId: string) => Promise<SessionState>,
): UseMutationResult<SessionState, Error, string> {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: action,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.connections });
      void queryClient.invalidateQueries({ queryKey: queryKeys.sessions });
    },
  });
}

export function useConnect(): UseMutationResult<SessionState, Error, string> {
  return useSessionMutation(connectConnection);
}

export function useDisconnect(): UseMutationResult<SessionState, Error, string> {
  return useSessionMutation(disconnectConnection);
}

export function useDeleteConnection(): UseMutationResult<void, Error, string> {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (connectionId: string) => deleteConnection(connectionId),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.connections });
      void queryClient.invalidateQueries({ queryKey: queryKeys.sessions });
    },
  });
}

export function useSaveConnection(): UseMutationResult<
  SaveConnectionResult,
  Error,
  SaveConnectionOptions
> {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (options: SaveConnectionOptions) => saveConnection(options),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.connections });
      void queryClient.invalidateQueries({ queryKey: queryKeys.sessions });
    },
  });
}

/**
 * A failed probe resolves with `success: false` rather than rejecting, so this mutation's `error`
 * means the request itself failed — check `data.success` for the cluster's answer.
 */
export function useTestConnection(): UseMutationResult<
  ConnectionTestResult,
  Error,
  Parameters<typeof testConnection>[0]
> {
  return useMutation({ mutationFn: (request) => testConnection(request) });
}
