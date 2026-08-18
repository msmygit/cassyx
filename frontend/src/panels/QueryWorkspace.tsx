import { useCallback, useEffect, useMemo, useState } from 'react';
import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Tab from '@mui/material/Tab';
import Tabs from '@mui/material/Tabs';
import { QueryEditor, DEFAULT_STATEMENT_OPTIONS, type StatementOptions } from './QueryEditor';
import { DataGrid } from './DataGrid';
import { QueryTracePanel } from './QueryTracePanel';
import { BatchBuilderDialog } from './BatchBuilderDialog';
import { QueryLibraryPanel } from './QueryLibraryPanel';
import { checkRowEditability, getQueryTrace } from '../query/api';
import { useQueryRunner, type ScriptRunMode } from '../query/useQueryRunner';
import type { QueryTrace } from '../query/types';

export interface QueryWorkspaceProps {
  connectionId: string | null;
  value: string;
  onChange: (value: string) => void;
  defaultKeyspace?: string;
}

/**
 * Editor + results + trace, wired to the query engine (plan §5.1, §7).
 *
 * Self-contained on purpose: the app shell renders it with a connection id and a tab's text, and
 * everything else — paging tokens, cancellation, editability, tracing — lives in here.
 */
export function QueryWorkspace({
  connectionId,
  value,
  onChange,
  defaultKeyspace,
}: QueryWorkspaceProps) {
  const [options, setOptions] = useState<StatementOptions>(DEFAULT_STATEMENT_OPTIONS);
  const [tab, setTab] = useState(0);
  const [trace, setTrace] = useState<QueryTrace | null>(null);
  const [traceError, setTraceError] = useState<string | null>(null);
  const [readOnlyReason, setReadOnlyReason] = useState<string | null>(null);
  const [batchOpen, setBatchOpen] = useState(false);

  const runner = useQueryRunner({ connectionId, fetchSize: options.fetchSize });
  const result = runner.result;

  const execute = useCallback(
    (statement: string, mode: ScriptRunMode, cursorOffset: number) => {
      setTrace(null);
      setTraceError(null);
      void runner.runScript(statement, mode, cursorOffset, {
        consistency: options.consistency,
        serialConsistency: options.serialConsistency,
        fetchSize: options.fetchSize,
        timeoutMillis: options.timeoutMillis,
        tracing: options.tracing,
        idempotent: options.idempotent,
        keyspace: defaultKeyspace,
      });
    },
    [defaultKeyspace, options, runner],
  );

  // The trace lands in system_traces slightly after the query returns, so it is fetched separately.
  useEffect(() => {
    if (!result?.tracingId || !result.queryId) return;
    let cancelled = false;
    getQueryTrace(result.queryId)
      .then((fetched) => {
        if (!cancelled) setTrace(fetched);
      })
      .catch((error: Error) => {
        if (!cancelled) setTraceError(error.message);
      });
    return () => {
      cancelled = true;
    };
  }, [result?.queryId, result?.tracingId]);

  // A result set that does not project the complete primary key cannot be edited, and the server
  // says exactly why (plan §7). The reason is shown verbatim rather than paraphrased.
  useEffect(() => {
    const source = result?.columns?.find((column) => column.keyspace && column.table);
    if (!connectionId || !result || !source?.keyspace || !source.table) {
      setReadOnlyReason(null);
      return;
    }
    let cancelled = false;
    checkRowEditability(
      connectionId,
      source.keyspace,
      source.table,
      result.columns.map((column) => column.name),
      result.resultHandle,
    )
      .then((verdict) => {
        if (!cancelled) setReadOnlyReason(verdict.editable ? null : (verdict.reason ?? null));
      })
      .catch(() => {
        if (!cancelled) setReadOnlyReason(null);
      });
    return () => {
      cancelled = true;
    };
  }, [connectionId, result]);

  const errorMessage = useMemo(() => runner.error?.message ?? null, [runner.error]);

  return (
    <Box
      sx={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column' }}
      data-testid="query-workspace"
    >
      <Box sx={{ flex: '1 1 45%', minHeight: 0, borderBottom: 1, borderColor: 'chrome.border' }}>
        <QueryEditor
          value={value}
          onChange={onChange}
          onExecute={execute}
          onCancel={() => void runner.cancel()}
          running={runner.running}
          options={options}
          onOptionsChange={setOptions}
          defaultKeyspace={defaultKeyspace}
          onOpenBatchBuilder={() => setBatchOpen(true)}
        />
      </Box>

      <Box sx={{ flex: '1 1 55%', minHeight: 0, display: 'flex', flexDirection: 'column' }}>
        {errorMessage && (
          <Alert severity="error" sx={{ m: 1 }} data-testid="query-error">
            {errorMessage}
          </Alert>
        )}
        <Tabs value={tab} onChange={(_, next) => setTab(next as number)} sx={{ minHeight: 36 }}>
          <Tab label="Results" sx={{ minHeight: 36 }} />
          <Tab label={result?.tracingId ? 'Trace ●' : 'Trace'} sx={{ minHeight: 36 }} />
          <Tab label="History & scripts" sx={{ minHeight: 36 }} />
        </Tabs>
        <Box sx={{ flex: 1, minHeight: 0 }}>
          {tab === 0 && (
            <DataGrid
              result={result}
              loading={runner.running}
              readOnlyReason={readOnlyReason}
              connectionId={connectionId}
              onNextPage={() => void runner.next()}
              onPreviousPage={() => void runner.previous()}
              onRefresh={() => execute(value, 'all', 0)}
            />
          )}
          {tab === 1 && <QueryTracePanel trace={trace} error={traceError} />}
          {tab === 2 && (
            <QueryLibraryPanel
              connectionId={connectionId}
              currentCql={value}
              onLoadScript={onChange}
            />
          )}
        </Box>
      </Box>

      <BatchBuilderDialog
        open={batchOpen}
        connectionId={connectionId}
        onClose={() => setBatchOpen(false)}
        onAssembled={(cql) => onChange(cql)}
      />
    </Box>
  );
}
