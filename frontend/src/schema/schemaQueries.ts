/**
 * TanStack Query bindings for the schema catalog (plan §4).
 *
 * The driver keeps a live, event-driven schema cache server-side, so these queries are cheap
 * reads rather than polls — the client caches them and invalidates after DDL rather than
 * refetching on a timer.
 */
import { useMutation, useQuery, useQueryClient, type UseQueryResult } from '@tanstack/react-query';
import { AppError } from '../api/errors';
import { toTree } from './fromApi';
import type { SchemaNode } from './model';
import {
  getSchemaTree,
  getTableInfo,
  getTableStatistics,
  searchSchema,
  updateTableComment,
} from './schemaApi';
import type {
  ApiSchemaSearchResult,
  DdlExecutionResult,
  TableInfo,
  TableStatistics,
} from './schemaTypes';

export const schemaKeys = {
  all: (connectionId: string) => ['schema', connectionId] as const,
  tree: (connectionId: string, includeSystem: boolean) =>
    ['schema', connectionId, 'tree', includeSystem] as const,
  search: (connectionId: string, q: string, includeSystem: boolean) =>
    ['schema', connectionId, 'search', q, includeSystem] as const,
  tableInfo: (connectionId: string, keyspace: string, table: string) =>
    ['schema', connectionId, 'table-info', keyspace, table] as const,
  tableStatistics: (connectionId: string, keyspace: string, table: string) =>
    ['schema', connectionId, 'table-statistics', keyspace, table] as const,
};

export function useSchemaTree(
  connectionId: string | null,
  includeSystem: boolean,
): UseQueryResult<SchemaNode[]> {
  return useQuery({
    queryKey: schemaKeys.tree(connectionId ?? '', includeSystem),
    enabled: Boolean(connectionId),
    queryFn: async () => toTree(await getSchemaTree(connectionId as string, includeSystem)),
  });
}

export function useSchemaSearch(
  connectionId: string | null,
  query: string,
  includeSystem: boolean,
): UseQueryResult<ApiSchemaSearchResult> {
  const trimmed = query.trim();
  return useQuery({
    queryKey: schemaKeys.search(connectionId ?? '', trimmed, includeSystem),
    enabled: Boolean(connectionId) && trimmed.length > 0,
    queryFn: () => searchSchema(connectionId as string, trimmed, { includeSystem }),
  });
}

export function useTableInfo(
  connectionId: string | null,
  keyspace: string | undefined,
  table: string | undefined,
): UseQueryResult<TableInfo> {
  return useQuery({
    queryKey: schemaKeys.tableInfo(connectionId ?? '', keyspace ?? '', table ?? ''),
    enabled: Boolean(connectionId && keyspace && table),
    queryFn: () => getTableInfo(connectionId as string, keyspace as string, table as string),
  });
}

/**
 * Statistics are a cached snapshot from a COUNT job. A 404 is the documented "not computed yet"
 * state, so it resolves to `null` rather than surfacing as an error the user cannot act on.
 */
export function useTableStatistics(
  connectionId: string | null,
  keyspace: string | undefined,
  table: string | undefined,
): UseQueryResult<TableStatistics | null> {
  return useQuery({
    queryKey: schemaKeys.tableStatistics(connectionId ?? '', keyspace ?? '', table ?? ''),
    enabled: Boolean(connectionId && keyspace && table),
    retry: false,
    queryFn: async () => {
      try {
        return await getTableStatistics(
          connectionId as string,
          keyspace as string,
          table as string,
        );
      } catch (error) {
        if (error instanceof AppError && error.status === 404) return null;
        throw error;
      }
    },
  });
}

export function useUpdateTableComment(
  connectionId: string,
  keyspace: string,
  table: string,
): ReturnType<typeof useMutation<DdlExecutionResult, Error, string>> {
  const queryClient = useQueryClient();
  return useMutation<DdlExecutionResult, Error, string>({
    mutationFn: (comment: string) => updateTableComment(connectionId, keyspace, table, { comment }),
    onSuccess: () => {
      void queryClient.invalidateQueries({
        queryKey: schemaKeys.tableInfo(connectionId, keyspace, table),
      });
    },
  });
}
