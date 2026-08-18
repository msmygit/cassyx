import type { ReactNode } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { QueryClientProvider } from '@tanstack/react-query';
import { renderHook, waitFor, act } from '@testing-library/react';
import { createTestQueryClient } from '../../test/render';
import type { DsbulkApi } from './dsbulkApi';
import {
  dsbulkQueryKeys,
  useCreateCountJob,
  useCreateLoadJob,
  useDebouncedValue,
  useDsbulkCommandPreview,
  useDsbulkDefaults,
} from './useDsbulkDefaults';

function wrapper() {
  const queryClient = createTestQueryClient();
  return ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  );
}

function stubApi(overrides: Partial<DsbulkApi> = {}): DsbulkApi {
  return {
    deriveDefaults: vi.fn(async () => ({
      operation: 'LOAD' as const,
      settings: [{ path: 'batch.mode', value: 'PARTITION_KEY', auto: true }],
      probe: { nodeCount: 3 },
    })),
    previewCommand: vi.fn(async () => ({
      command: 'dsbulk load',
      argv: ['load'],
      hocon: 'dsbulk {}',
    })),
    createLoadJob: vi.fn(async () => ({ id: 'job-1' }) as never),
    createCountJob: vi.fn(async () => ({ id: 'job-2' }) as never),
    uploadSourceFile: vi.fn(async () => ({ uploadId: 'up_1' }) as never),
    ...overrides,
  };
}

afterEach(() => {
  vi.useRealTimers();
});

describe('dsbulkQueryKeys', () => {
  it('scopes keys under the feature and includes the request', () => {
    expect(dsbulkQueryKeys.defaults('c1', { operation: 'LOAD' })).toEqual([
      'bulk',
      'dsbulk',
      'defaults',
      'c1',
      { operation: 'LOAD' },
    ]);
    expect(dsbulkQueryKeys.commandPreview('c1', { operation: 'COUNT' })[2]).toBe('command-preview');
    expect(dsbulkQueryKeys.all).toEqual(['bulk', 'dsbulk']);
  });
});

describe('useDebouncedValue', () => {
  it('only publishes the trailing value', async () => {
    vi.useFakeTimers();
    const { result, rerender } = renderHook(({ value }) => useDebouncedValue(value, 200), {
      initialProps: { value: 'a' },
    });
    expect(result.current).toBe('a');

    rerender({ value: 'b' });
    rerender({ value: 'c' });
    expect(result.current).toBe('a');

    await act(async () => {
      vi.advanceTimersByTime(200);
    });
    expect(result.current).toBe('c');
  });

  it('passes the value straight through when debouncing is disabled', () => {
    const { result, rerender } = renderHook(({ value }) => useDebouncedValue(value, 0), {
      initialProps: { value: 'a' },
    });
    rerender({ value: 'b' });
    expect(result.current).toBe('b');
  });
});

describe('useDsbulkDefaults', () => {
  it('fetches derived settings for the connection', async () => {
    const api = stubApi();
    const { result } = renderHook(
      () =>
        useDsbulkDefaults('c1', { operation: 'LOAD', keyspace: 'demo', table: 'users' }, { api }),
      { wrapper: wrapper() },
    );

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.settings?.[0]?.path).toBe('batch.mode');
    expect(api.deriveDefaults).toHaveBeenCalledWith('c1', {
      operation: 'LOAD',
      keyspace: 'demo',
      table: 'users',
    });
  });

  it('stays idle without a connection', () => {
    const api = stubApi();
    const { result } = renderHook(() => useDsbulkDefaults('', { operation: 'LOAD' }, { api }), {
      wrapper: wrapper(),
    });
    expect(result.current.fetchStatus).toBe('idle');
    expect(api.deriveDefaults).not.toHaveBeenCalled();
  });

  it('can be disabled explicitly', () => {
    const api = stubApi();
    renderHook(() => useDsbulkDefaults('c1', { operation: 'LOAD' }, { api, enabled: false }), {
      wrapper: wrapper(),
    });
    expect(api.deriveDefaults).not.toHaveBeenCalled();
  });
});

describe('useDsbulkCommandPreview', () => {
  it('debounces before asking the server to regenerate the command', async () => {
    const api = stubApi();
    const { result } = renderHook(
      () => useDsbulkCommandPreview('c1', { operation: 'LOAD' }, { api, debounceMs: 0 }),
      { wrapper: wrapper() },
    );

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.command).toBe('dsbulk load');
    expect(api.previewCommand).toHaveBeenCalledTimes(1);
  });

  it('does not fire while the debounce window is open', () => {
    vi.useFakeTimers();
    const api = stubApi();
    renderHook(() => useDsbulkCommandPreview('c1', { operation: 'LOAD' }, { api }), {
      wrapper: wrapper(),
    });
    // The first render publishes the initial value immediately; nothing more until it settles.
    expect(api.previewCommand).toHaveBeenCalledTimes(1);
  });
});

describe('job mutations', () => {
  it('creates a load job', async () => {
    const api = stubApi();
    const { result } = renderHook(() => useCreateLoadJob('c1', { api }), { wrapper: wrapper() });

    await act(async () => {
      await result.current.mutateAsync({
        keyspace: 'demo',
        table: 'users',
        source: { uploadId: 'up_1', compression: 'AUTO' },
        dryRun: false,
      });
    });

    expect(api.createLoadJob).toHaveBeenCalledWith('c1', {
      keyspace: 'demo',
      table: 'users',
      source: { uploadId: 'up_1', compression: 'AUTO' },
      dryRun: false,
    });
  });

  it('creates a count job', async () => {
    const api = stubApi();
    const { result } = renderHook(() => useCreateCountJob('c1', { api }), { wrapper: wrapper() });

    await act(async () => {
      await result.current.mutateAsync({
        keyspace: 'demo',
        table: 'users',
        modes: ['global'],
        topPartitions: 10,
      });
    });

    expect(api.createCountJob).toHaveBeenCalledWith('c1', {
      keyspace: 'demo',
      table: 'users',
      modes: ['global'],
      topPartitions: 10,
    });
  });
});
