import { describe, expect, it } from 'vitest';
import {
  initialQueryRunnerState,
  newQueryId,
  queryRunnerReducer,
  type QueryRunnerState,
} from './useQueryRunner';
import type { QueryResult } from './types';

const result = (over: Partial<QueryResult> = {}): QueryResult =>
  ({
    resultHandle: 'rs_1',
    queryId: 'q-1',
    columns: [],
    rows: [],
    rowCount: 0,
    hasMorePages: false,
    ...over,
  }) as QueryResult;

describe('queryRunnerReducer', () => {
  it('records the statement and the cancellable query id on start', () => {
    const state = queryRunnerReducer(initialQueryRunnerState, {
      type: 'start',
      queryId: 'q-1',
      cql: 'SELECT 1',
    });

    expect(state.running).toBe(true);
    expect(state.queryId).toBe('q-1');
    expect(state.ranStatements).toEqual(['SELECT 1']);
    expect(state.error).toBeNull();
  });

  it('clears the error on success and the result stays on failure', () => {
    const started = queryRunnerReducer(initialQueryRunnerState, {
      type: 'start',
      queryId: 'q-1',
      cql: 'SELECT 1',
    });
    const succeeded = queryRunnerReducer(started, { type: 'succeeded', result: result() });
    expect(succeeded.running).toBe(false);
    expect(succeeded.result?.resultHandle).toBe('rs_1');
    expect(succeeded.queryId).toBeNull();

    const failed = queryRunnerReducer(succeeded, { type: 'failed', error: new Error('boom') });
    expect(failed.error?.message).toBe('boom');
    // The previous page stays on screen: losing it on every error makes the tool unusable.
    expect(failed.result?.resultHandle).toBe('rs_1');
  });

  it('tracks the cancelling state separately from running', () => {
    const started = queryRunnerReducer(initialQueryRunnerState, {
      type: 'start',
      queryId: 'q-1',
      cql: 'SELECT 1',
    });
    const cancelling = queryRunnerReducer(started, { type: 'cancelling' });
    expect(cancelling.cancelling).toBe(true);
    expect(cancelling.running).toBe(true);

    const cancelled = queryRunnerReducer(cancelling, { type: 'cancelled' });
    expect(cancelled.running).toBe(false);
    expect(cancelled.cancelling).toBe(false);
    expect(cancelled.queryId).toBeNull();
  });

  it('resets to the initial state and ignores unknown actions', () => {
    const started = queryRunnerReducer(initialQueryRunnerState, {
      type: 'start',
      queryId: 'q',
      cql: 'SELECT 1',
    });
    expect(queryRunnerReducer(started, { type: 'reset' })).toEqual(initialQueryRunnerState);
    const unknown = { type: 'nope' } as unknown as Parameters<typeof queryRunnerReducer>[1];
    expect(queryRunnerReducer(started, unknown)).toBe(started as QueryRunnerState);
  });
});

describe('newQueryId', () => {
  it('produces a distinct RFC 4122 shaped id', () => {
    const a = newQueryId();
    const b = newQueryId();
    expect(a).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/);
    expect(a).not.toBe(b);
  });

  it('falls back when crypto.randomUUID is unavailable', () => {
    const original = globalThis.crypto;
    Object.defineProperty(globalThis, 'crypto', { value: {}, configurable: true });
    try {
      expect(newQueryId()).toMatch(
        /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/,
      );
    } finally {
      Object.defineProperty(globalThis, 'crypto', { value: original, configurable: true });
    }
  });
});
