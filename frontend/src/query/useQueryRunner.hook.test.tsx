import { act, renderHook, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { useQueryRunner } from './useQueryRunner';
import type { QueryResult } from './types';

vi.mock('./api', () => ({
  executeQuery: vi.fn(),
  fetchNextPage: vi.fn(),
  fetchPreviousPage: vi.fn(),
  cancelQuery: vi.fn(),
  closeResultSet: vi.fn(),
  splitCqlScript: vi.fn(),
}));

import {
  cancelQuery,
  closeResultSet,
  executeQuery,
  fetchNextPage,
  fetchPreviousPage,
  splitCqlScript,
} from './api';

const page = (over: Partial<QueryResult> = {}): QueryResult =>
  ({
    resultHandle: 'rs_1',
    queryId: 'q-1',
    columns: [],
    rows: [],
    rowCount: 0,
    hasMorePages: false,
    ...over,
  }) as QueryResult;

describe('useQueryRunner', () => {
  beforeEach(() => {
    vi.mocked(executeQuery).mockReset();
    vi.mocked(fetchNextPage).mockReset();
    vi.mocked(fetchPreviousPage).mockReset();
    vi.mocked(cancelQuery).mockReset().mockResolvedValue({ queryId: 'q', cancelled: true });
    vi.mocked(closeResultSet).mockReset().mockResolvedValue(undefined);
    vi.mocked(splitCqlScript).mockReset();
  });

  it('refuses to run without a connection instead of firing a request', async () => {
    const { result } = renderHook(() => useQueryRunner({ connectionId: null }));

    await act(async () => {
      await result.current.run({ cql: 'SELECT 1' });
    });

    expect(executeQuery).not.toHaveBeenCalled();
    expect(result.current.error?.message).toMatch(/Connect to a cluster/);
  });

  it('runs a statement and exposes the paging affordances of the result', async () => {
    vi.mocked(executeQuery).mockResolvedValue(page({ nextPageToken: 'tok', hasMorePages: true }));
    const { result } = renderHook(() => useQueryRunner({ connectionId: 'c1', fetchSize: 250 }));

    await act(async () => {
      await result.current.run({ cql: 'SELECT 1' });
    });

    expect(executeQuery).toHaveBeenCalledWith(
      'c1',
      expect.objectContaining({ cql: 'SELECT 1', fetchSize: 250 }),
      expect.objectContaining({ queryId: expect.any(String) }),
    );
    expect(result.current.canGoNext).toBe(true);
    expect(result.current.canGoPrevious).toBe(false);
    expect(result.current.running).toBe(false);
  });

  it('surfaces an execution failure without discarding the page already on screen', async () => {
    vi.mocked(executeQuery).mockResolvedValueOnce(page());
    const { result } = renderHook(() => useQueryRunner({ connectionId: 'c1' }));
    await act(async () => {
      await result.current.run({ cql: 'SELECT 1' });
    });

    vi.mocked(executeQuery).mockRejectedValueOnce(new Error('syntax error'));
    await act(async () => {
      await result.current.run({ cql: 'SELECT nope' });
    });

    expect(result.current.error?.message).toBe('syntax error');
    expect(result.current.result?.resultHandle).toBe('rs_1');
  });

  it('releases the previous result handle when a new statement replaces it', async () => {
    vi.mocked(executeQuery)
      .mockResolvedValueOnce(page({ resultHandle: 'rs_1' }))
      .mockResolvedValueOnce(page({ resultHandle: 'rs_2' }));
    const { result } = renderHook(() => useQueryRunner({ connectionId: 'c1' }));

    await act(async () => {
      await result.current.run({ cql: 'SELECT 1' });
    });
    await act(async () => {
      await result.current.run({ cql: 'SELECT 2' });
    });

    await waitFor(() => expect(closeResultSet).toHaveBeenCalledWith('rs_1'));
  });

  it('pages forward and backward with the opaque tokens, and does nothing without one', async () => {
    vi.mocked(executeQuery).mockResolvedValue(
      page({ nextPageToken: 'next-1', hasMorePages: true }),
    );
    vi.mocked(fetchNextPage).mockResolvedValue(
      page({ pageNumber: 2, previousPageToken: 'prev-1', nextPageToken: null }),
    );
    vi.mocked(fetchPreviousPage).mockResolvedValue(page({ pageNumber: 1 }));

    const { result } = renderHook(() => useQueryRunner({ connectionId: 'c1', fetchSize: 100 }));
    await act(async () => {
      await result.current.run({ cql: 'SELECT 1' });
    });

    await act(async () => {
      await result.current.next();
    });
    expect(fetchNextPage).toHaveBeenCalledWith('rs_1', 'next-1', 100);
    expect(result.current.result?.pageNumber).toBe(2);

    await act(async () => {
      await result.current.previous();
    });
    expect(fetchPreviousPage).toHaveBeenCalledWith('rs_1', 'prev-1', 100);

    // On page 1 there is no previous token, so nothing is requested.
    vi.mocked(fetchPreviousPage).mockClear();
    await act(async () => {
      await result.current.previous();
    });
    expect(fetchPreviousPage).not.toHaveBeenCalled();
  });

  it('reports a paging failure rather than silently keeping the old page', async () => {
    vi.mocked(executeQuery).mockResolvedValue(page({ nextPageToken: 'tok', hasMorePages: true }));
    vi.mocked(fetchNextPage).mockRejectedValue(new Error('Result set expired'));
    const { result } = renderHook(() => useQueryRunner({ connectionId: 'c1' }));
    await act(async () => {
      await result.current.run({ cql: 'SELECT 1' });
    });

    await act(async () => {
      await result.current.next();
    });

    expect(result.current.error?.message).toBe('Result set expired');
  });

  it('cancels the in-flight execution both client-side and server-side', async () => {
    let resolve: ((value: QueryResult) => void) | undefined;
    vi.mocked(executeQuery).mockImplementation(
      () =>
        new Promise<QueryResult>((r) => {
          resolve = r;
        }),
    );
    const { result } = renderHook(() => useQueryRunner({ connectionId: 'c1' }));

    let pending: Promise<unknown> | undefined;
    await act(async () => {
      pending = result.current.run({ cql: 'SELECT 1' });
    });
    await waitFor(() => expect(result.current.running).toBe(true));

    await act(async () => {
      await result.current.cancel();
    });

    expect(cancelQuery).toHaveBeenCalledTimes(1);
    expect(result.current.running).toBe(false);

    await act(async () => {
      resolve?.(page());
      await pending;
    });
  });

  it('runs every statement of a script, in order, using the server-side lexer', async () => {
    vi.mocked(splitCqlScript).mockResolvedValue({
      statements: [
        { index: 0, cql: "SELECT * FROM t WHERE a = 'x;y'", startOffset: 0, endOffset: 30 },
        { index: 1, cql: 'SELECT 1', startOffset: 32, endOffset: 40 },
      ],
    });
    vi.mocked(executeQuery).mockResolvedValue(page());
    const { result } = renderHook(() => useQueryRunner({ connectionId: 'c1' }));

    await act(async () => {
      await result.current.runScript("SELECT * FROM t WHERE a = 'x;y'; SELECT 1;", 'all', 0);
    });

    expect(executeQuery).toHaveBeenCalledTimes(2);
    expect(result.current.ranStatements).toEqual(["SELECT * FROM t WHERE a = 'x;y'", 'SELECT 1']);
  });

  it('runs only the statement under the cursor when asked to', async () => {
    vi.mocked(splitCqlScript).mockResolvedValue({
      statements: [
        { index: 0, cql: 'SELECT 1', startOffset: 0, endOffset: 8, underCursor: false },
        { index: 1, cql: 'SELECT 2', startOffset: 10, endOffset: 18, underCursor: true },
      ],
    });
    vi.mocked(executeQuery).mockResolvedValue(page());
    const { result } = renderHook(() => useQueryRunner({ connectionId: 'c1' }));

    await act(async () => {
      await result.current.runScript('SELECT 1; SELECT 2;', 'cursor', 12);
    });

    expect(executeQuery).toHaveBeenCalledTimes(1);
    expect(result.current.ranStatements).toEqual(['SELECT 2']);
  });

  it('falls back to the first statement when the cursor matches none', async () => {
    vi.mocked(splitCqlScript).mockResolvedValue({
      statements: [{ index: 0, cql: 'SELECT 1', startOffset: 0, endOffset: 8 }],
    });
    vi.mocked(executeQuery).mockResolvedValue(page());
    const { result } = renderHook(() => useQueryRunner({ connectionId: 'c1' }));

    await act(async () => {
      await result.current.runScript('SELECT 1;', 'cursor');
    });

    expect(result.current.ranStatements).toEqual(['SELECT 1']);
  });

  it('stops a script run at the first failure', async () => {
    vi.mocked(splitCqlScript).mockResolvedValue({
      statements: [
        { index: 0, cql: 'SELECT 1', startOffset: 0, endOffset: 8 },
        { index: 1, cql: 'SELECT 2', startOffset: 10, endOffset: 18 },
      ],
    });
    vi.mocked(executeQuery).mockRejectedValue(new Error('boom'));
    const { result } = renderHook(() => useQueryRunner({ connectionId: 'c1' }));

    await act(async () => {
      await result.current.runScript('SELECT 1; SELECT 2;', 'all', 0);
    });

    expect(executeQuery).toHaveBeenCalledTimes(1);
  });

  it('resets back to the empty state and releases the handle', async () => {
    vi.mocked(executeQuery).mockResolvedValue(page());
    const { result } = renderHook(() => useQueryRunner({ connectionId: 'c1' }));
    await act(async () => {
      await result.current.run({ cql: 'SELECT 1' });
    });

    act(() => {
      result.current.reset();
    });

    expect(result.current.result).toBeNull();
    await waitFor(() => expect(closeResultSet).toHaveBeenCalledWith('rs_1'));
  });
});
