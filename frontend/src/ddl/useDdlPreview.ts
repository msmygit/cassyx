import { useCallback, useEffect, useRef, useState } from 'react';
import { AppError } from '../api/errors';
import { executeDdl, generateDdl } from '../schema/schemaApi';
import type { DdlAction, DdlExecutionResult, DdlObjectType } from '../schema/schemaTypes';
import { generateRequest, validate, type DdlTarget } from './ddlModel';

export interface UseDdlPreviewOptions {
  connectionId: string;
  objectType: DdlObjectType;
  action: DdlAction;
  target: DdlTarget;
  definition: Record<string, unknown>;
  /** Debounce for the generate call while the user types. */
  debounceMs?: number;
  onExecuted?: (result: DdlExecutionResult) => void;
}

export interface DdlPreviewState {
  cql: string;
  setCql: (cql: string) => void;
  warnings: string[];
  problems: string[];
  error: string | null;
  loading: boolean;
  executing: boolean;
  result: DdlExecutionResult | null;
  execute: () => Promise<void>;
  /** True once the user has edited the generated CQL, so regeneration stops clobbering them. */
  edited: boolean;
}

/**
 * Keeps a live "Preview CQL" pane in sync with a visual editor, then executes exactly what is in
 * the pane (plan §4).
 *
 * Generation is server-side (`POST /ddl/generate`) so the browser never becomes a second, subtly
 * different CQL renderer. Once the user edits the pane the generator stops overwriting it: their
 * text is the source of truth from that point on.
 */
export function useDdlPreview(options: UseDdlPreviewOptions): DdlPreviewState {
  const {
    connectionId,
    objectType,
    action,
    target,
    definition,
    debounceMs = 200,
    onExecuted,
  } = options;

  const [cql, setCqlState] = useState('');
  const [warnings, setWarnings] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [executing, setExecuting] = useState(false);
  const [result, setResult] = useState<DdlExecutionResult | null>(null);
  const [edited, setEdited] = useState(false);

  const editedRef = useRef(false);
  const requestSeq = useRef(0);

  const problems = validate(objectType, action, definition);
  const serialisedDefinition = JSON.stringify(definition);
  const serialisedTarget = JSON.stringify(target);

  const setCql = useCallback((next: string) => {
    editedRef.current = true;
    setEdited(true);
    setCqlState(next);
  }, []);

  useEffect(() => {
    if (editedRef.current) return undefined;
    if (problems.length > 0) {
      setCqlState('');
      setWarnings([]);
      setError(null);
      return undefined;
    }

    const seq = ++requestSeq.current;
    const timer = setTimeout(() => {
      setLoading(true);
      generateDdl(
        connectionId,
        generateRequest(
          objectType,
          action,
          JSON.parse(serialisedTarget) as DdlTarget,
          JSON.parse(serialisedDefinition) as Record<string, unknown>,
        ),
      )
        .then((preview) => {
          if (seq !== requestSeq.current || editedRef.current) return;
          setCqlState(preview.cql);
          setWarnings(preview.warnings ?? []);
          setError(null);
        })
        .catch((cause: unknown) => {
          if (seq !== requestSeq.current) return;
          setError(messageOf(cause));
        })
        .finally(() => {
          if (seq === requestSeq.current) setLoading(false);
        });
    }, debounceMs);

    return () => clearTimeout(timer);
    // `problems` is derived from the serialised definition, so it needs no separate dependency
    // beyond its length.
  }, [
    connectionId,
    objectType,
    action,
    serialisedTarget,
    serialisedDefinition,
    debounceMs,
    problems.length,
  ]);

  const execute = useCallback(async () => {
    if (!cql.trim()) return;
    setExecuting(true);
    setError(null);
    try {
      const executed = await executeDdl(connectionId, { cql, stopOnError: true });
      setResult(executed);
      onExecuted?.(executed);
    } catch (cause: unknown) {
      setError(messageOf(cause));
    } finally {
      setExecuting(false);
    }
  }, [connectionId, cql, onExecuted]);

  return {
    cql,
    setCql,
    warnings,
    problems,
    error,
    loading,
    executing,
    result,
    execute,
    edited,
  };
}

function messageOf(cause: unknown): string {
  if (cause instanceof AppError) {
    return cause.problem?.detail ?? cause.message;
  }
  return cause instanceof Error ? cause.message : String(cause);
}
