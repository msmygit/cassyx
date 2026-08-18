/**
 * TanStack Query hooks for the DSBulk settings UI (plan §5.3).
 *
 * Two server round-trips power the form:
 *   • `deriveBulkDefaults` — probes the cluster and returns every setting with its `auto` marker.
 *   • `previewBulkCommand` — regenerates the command/HOCON as the user types, so it is DEBOUNCED.
 *
 * Query keys follow the shell's `queryKeys` factory shape in `src/api/queryClient.ts`.
 */
import { useEffect, useRef, useState } from 'react';
import { useMutation, useQuery, type UseQueryResult } from '@tanstack/react-query';
import {
  defaultDsbulkApi,
  type BulkCommandPreview,
  type BulkCommandPreviewRequest,
  type BulkDefaultsRequest,
  type CountJobRequest,
  type DerivedSettingsResponse,
  type DsbulkApi,
  type Job,
  type LoadJobRequest,
} from './dsbulkApi';

export const dsbulkQueryKeys = {
  all: ['bulk', 'dsbulk'] as const,
  defaults: (connectionId: string, request: BulkDefaultsRequest) =>
    ['bulk', 'dsbulk', 'defaults', connectionId, request] as const,
  commandPreview: (connectionId: string, request: BulkCommandPreviewRequest) =>
    ['bulk', 'dsbulk', 'command-preview', connectionId, request] as const,
};

/** Structural identity, so a freshly-built request object does not look like a change. */
function stableKey(value: unknown): string {
  try {
    return JSON.stringify(value) ?? String(value);
  } catch {
    return String(value);
  }
}

/**
 * Trailing-edge debounce, so a keystroke in the settings form does not trigger a command-preview
 * request per character.
 *
 * Debouncing is keyed on the value's STRUCTURE, not its identity: callers build the request object
 * inline on every render, and keying on identity would re-arm the timer forever.
 */
export function useDebouncedValue<T>(value: T, delayMs: number): T {
  const [debounced, setDebounced] = useState(value);
  const latest = useRef(value);
  latest.current = value;
  const key = stableKey(value);

  useEffect(() => {
    if (delayMs <= 0) {
      setDebounced(latest.current);
      return;
    }
    const timer = setTimeout(() => setDebounced(latest.current), delayMs);
    return () => clearTimeout(timer);
  }, [key, delayMs]);

  return debounced;
}

export interface DsbulkHookOptions {
  api?: DsbulkApi;
  enabled?: boolean;
}

/**
 * Derived defaults for a prospective job. `request.overrides` is included in the key, so changing
 * an override re-derives — the server reports the caller's own values back with `auto: false`.
 */
export function useDsbulkDefaults(
  connectionId: string,
  request: BulkDefaultsRequest,
  options: DsbulkHookOptions = {},
): UseQueryResult<DerivedSettingsResponse> {
  const { api = defaultDsbulkApi, enabled = true } = options;
  return useQuery({
    queryKey: dsbulkQueryKeys.defaults(connectionId, request),
    queryFn: () => api.deriveDefaults(connectionId, request),
    enabled: enabled && connectionId !== '',
  });
}

export interface CommandPreviewOptions extends DsbulkHookOptions {
  /** Debounce applied to the request before it is sent. Defaults to 400ms. */
  debounceMs?: number;
}

export function useDsbulkCommandPreview(
  connectionId: string,
  request: BulkCommandPreviewRequest,
  options: CommandPreviewOptions = {},
): UseQueryResult<BulkCommandPreview> {
  const { api = defaultDsbulkApi, enabled = true, debounceMs = 400 } = options;
  const debounced = useDebouncedValue(request, debounceMs);

  return useQuery({
    queryKey: dsbulkQueryKeys.commandPreview(connectionId, debounced),
    queryFn: () => api.previewCommand(connectionId, debounced),
    enabled: enabled && connectionId !== '',
  });
}

export function useCreateLoadJob(connectionId: string, options: DsbulkHookOptions = {}) {
  const { api = defaultDsbulkApi } = options;
  return useMutation<Job, Error, LoadJobRequest>({
    mutationFn: (request) => api.createLoadJob(connectionId, request),
  });
}

export function useCreateCountJob(connectionId: string, options: DsbulkHookOptions = {}) {
  const { api = defaultDsbulkApi } = options;
  return useMutation<Job, Error, CountJobRequest>({
    mutationFn: (request) => api.createCountJob(connectionId, request),
  });
}
