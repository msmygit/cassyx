/**
 * The query-execution state machine behind the editor and the grid.
 *
 * Split into a pure reducer plus a thin hook so the interesting behaviour — paging tokens,
 * cancellation, error recovery — is unit-testable without React.
 */
import { useCallback, useMemo, useReducer, useRef } from 'react';
import type { AppError } from '../api/errors';
import {
  cancelQuery,
  closeResultSet,
  executeQuery,
  fetchNextPage,
  fetchPreviousPage,
  splitCqlScript,
} from './api';
import {
  DEFAULT_FETCH_SIZE,
  type CqlStatementSlice,
  type QueryRequestInput,
  type QueryResult,
} from './types';

export interface QueryRunnerState {
  running: boolean;
  /** Execution id of the in-flight query, so it can be cancelled while it runs. */
  queryId: string | null;
  result: QueryResult | null;
  error: AppError | Error | null;
  /** Statements executed so far in a multi-statement run, newest last. */
  ranStatements: string[];
  cancelling: boolean;
}

export const initialQueryRunnerState: QueryRunnerState = {
  running: false,
  queryId: null,
  result: null,
  error: null,
  ranStatements: [],
  cancelling: false,
};

export type QueryRunnerAction =
  | { type: 'start'; queryId: string; cql: string }
  | { type: 'succeeded'; result: QueryResult }
  | { type: 'failed'; error: AppError | Error }
  | { type: 'cancelling' }
  | { type: 'cancelled' }
  | { type: 'reset' };

export function queryRunnerReducer(
  state: QueryRunnerState,
  action: QueryRunnerAction,
): QueryRunnerState {
  switch (action.type) {
    case 'start':
      return {
        ...state,
        running: true,
        cancelling: false,
        queryId: action.queryId,
        error: null,
        ranStatements: [...state.ranStatements, action.cql],
      };
    case 'succeeded':
      return {
        ...state,
        running: false,
        cancelling: false,
        queryId: null,
        result: action.result,
        error: null,
      };
    case 'failed':
      return { ...state, running: false, cancelling: false, queryId: null, error: action.error };
    case 'cancelling':
      return { ...state, cancelling: true };
    case 'cancelled':
      return { ...state, running: false, cancelling: false, queryId: null };
    case 'reset':
      return initialQueryRunnerState;
    default:
      return state;
  }
}

/** RFC 4122 v4 id without pulling in a dependency; `crypto.randomUUID` where available. */
export function newQueryId(): string {
  const cryptoApi = globalThis.crypto as Crypto | undefined;
  if (cryptoApi?.randomUUID) return cryptoApi.randomUUID();
  const bytes = new Uint8Array(16);
  for (let i = 0; i < bytes.length; i += 1) bytes[i] = Math.floor(Math.random() * 256);
  bytes[6] = ((bytes[6] as number) & 0x0f) | 0x40;
  bytes[8] = ((bytes[8] as number) & 0x3f) | 0x80;
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

export type RunOptions = QueryRequestInput;

/** Statement-level controls shared by every statement of a script run. */
export type RunDefaults = Omit<QueryRequestInput, 'cql'>;

export interface QueryRunner extends QueryRunnerState {
  run: (request: RunOptions) => Promise<QueryResult | null>;
  runScript: (
    script: string,
    mode: ScriptRunMode,
    cursorOffset?: number,
    base?: RunDefaults,
  ) => Promise<QueryResult | null>;
  cancel: () => Promise<void>;
  next: () => Promise<QueryResult | null>;
  previous: () => Promise<QueryResult | null>;
  reset: () => void;
  canGoNext: boolean;
  canGoPrevious: boolean;
}

export type ScriptRunMode = 'all' | 'cursor' | 'selection';

export interface QueryRunnerOptions {
  connectionId: string | null;
  fetchSize?: number;
}

export function useQueryRunner(options: QueryRunnerOptions): QueryRunner {
  const [state, dispatch] = useReducer(queryRunnerReducer, initialQueryRunnerState);
  const abortRef = useRef<AbortController | null>(null);
  const handleRef = useRef<string | null>(null);
  const fetchSize = options.fetchSize ?? DEFAULT_FETCH_SIZE;

  const releasePrevious = useCallback((nextHandle: string | null) => {
    const previous = handleRef.current;
    handleRef.current = nextHandle;
    if (previous && previous !== nextHandle) {
      // Best effort: the handle expires on its own TTL anyway, so a failure here is not an error
      // the user needs to see.
      void closeResultSet(previous).catch(() => undefined);
    }
  }, []);

  const run = useCallback(
    async (request: RunOptions): Promise<QueryResult | null> => {
      if (!options.connectionId) {
        dispatch({
          type: 'failed',
          error: new Error('Connect to a cluster before running a query.'),
        });
        return null;
      }
      const queryId = newQueryId();
      const controller = new AbortController();
      abortRef.current = controller;
      dispatch({ type: 'start', queryId, cql: request.cql });
      try {
        const result = await executeQuery(
          options.connectionId,
          { fetchSize, ...request } as QueryRequestInput,
          { queryId, signal: controller.signal },
        );
        releasePrevious(result.resultHandle);
        dispatch({ type: 'succeeded', result });
        return result;
      } catch (error) {
        dispatch({ type: 'failed', error: error as Error });
        return null;
      } finally {
        abortRef.current = null;
      }
    },
    [fetchSize, options.connectionId, releasePrevious],
  );

  const runScript = useCallback(
    async (
      script: string,
      mode: ScriptRunMode,
      cursorOffset?: number,
      base: RunDefaults = {},
    ): Promise<QueryResult | null> => {
      const statements = await selectStatements(script, mode, cursorOffset);
      let last: QueryResult | null = null;
      for (const statement of statements) {
        last = await run({ ...base, cql: statement.cql });
        if (!last) break;
      }
      return last;
    },
    [run],
  );

  const cancel = useCallback(async () => {
    const queryId = state.queryId;
    dispatch({ type: 'cancelling' });
    abortRef.current?.abort();
    if (queryId) {
      await cancelQuery(queryId).catch(() => undefined);
    }
    dispatch({ type: 'cancelled' });
  }, [state.queryId]);

  const page = useCallback(
    async (direction: 'next' | 'previous'): Promise<QueryResult | null> => {
      const current = state.result;
      const token = direction === 'next' ? current?.nextPageToken : current?.previousPageToken;
      if (!current || !token) return null;
      try {
        const fetcher = direction === 'next' ? fetchNextPage : fetchPreviousPage;
        const result = await fetcher(current.resultHandle, token, fetchSize);
        dispatch({ type: 'succeeded', result });
        return result;
      } catch (error) {
        dispatch({ type: 'failed', error: error as Error });
        return null;
      }
    },
    [fetchSize, state.result],
  );

  const next = useCallback(() => page('next'), [page]);
  const previous = useCallback(() => page('previous'), [page]);
  const reset = useCallback(() => {
    releasePrevious(null);
    dispatch({ type: 'reset' });
  }, [releasePrevious]);

  return useMemo(
    () => ({
      ...state,
      run,
      runScript,
      cancel,
      next,
      previous,
      reset,
      canGoNext: Boolean(state.result?.nextPageToken),
      canGoPrevious: Boolean(state.result?.previousPageToken),
    }),
    [cancel, next, previous, reset, run, runScript, state],
  );
}

/**
 * Chooses which statements a run covers.
 *
 * Splitting happens on the SERVER, through the real CQL lexer. Doing it in the browser with
 * `split(';')` is exactly the bug plan §5.1 calls out — string literals and UDF bodies contain
 * semicolons.
 */
export async function selectStatements(
  script: string,
  mode: ScriptRunMode,
  cursorOffset?: number,
): Promise<CqlStatementSlice[]> {
  if (mode === 'selection') {
    const result = await splitCqlScript(script);
    return result.statements;
  }
  const result = await splitCqlScript(script, cursorOffset);
  if (mode === 'all') return result.statements;
  const underCursor = result.statements.filter((statement) => statement.underCursor);
  return underCursor.length > 0 ? underCursor : result.statements.slice(0, 1);
}
