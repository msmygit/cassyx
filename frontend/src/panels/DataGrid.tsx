import { useMemo, useRef, useState } from 'react';
import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Chip from '@mui/material/Chip';
import Divider from '@mui/material/Divider';
import IconButton from '@mui/material/IconButton';
import InputAdornment from '@mui/material/InputAdornment';
import Stack from '@mui/material/Stack';
import TextField from '@mui/material/TextField';
import ToggleButton from '@mui/material/ToggleButton';
import ToggleButtonGroup from '@mui/material/ToggleButtonGroup';
import Tooltip from '@mui/material/Tooltip';
import Typography from '@mui/material/Typography';
import ChevronLeftRoundedIcon from '@mui/icons-material/ChevronLeftRounded';
import ChevronRightRoundedIcon from '@mui/icons-material/ChevronRightRounded';
import SearchRoundedIcon from '@mui/icons-material/SearchRounded';
import TableRowsRoundedIcon from '@mui/icons-material/TableRowsRounded';
import ViewAgendaRoundedIcon from '@mui/icons-material/ViewAgendaRounded';
import { useVirtualizer } from '@tanstack/react-virtual';
import { getCoreRowModel, useReactTable, type ColumnDef } from '@tanstack/react-table';
import { PanelPlaceholder } from './PanelPlaceholder';
import { DataGridCell } from './DataGridCell';
import { RowEditorDialog, type RowEditorMode } from './RowEditorDialog';
import { applyResultView, nextSort, toCsv, type SortSpec } from '../query/resultView';
import { generateRowStatements } from '../query/api';
import type { ColumnMetadata, QueryResult, ResultRow, StatementKind } from '../query/types';

export type DataGridRow = ResultRow;

export interface DataGridProps {
  /** Full server result, including paging tokens and the LWT `[applied]` flag. */
  result?: QueryResult | null;
  /** Legacy shell props, kept so the Phase 0 workspace keeps compiling. */
  columns?: string[];
  rows?: DataGridRow[];
  onNextPage?: () => void;
  onPreviousPage?: () => void;
  /** Verbatim server explanation of why this result set is read-only, if it is. */
  readOnlyReason?: string | null;
  loading?: boolean;
  /** Enables row CRUD. Without it the grid is a viewer, which is the correct default. */
  connectionId?: string | null;
  /** Re-run the current statement after a mutation. */
  onRefresh?: () => void;
}

const ROW_HEIGHT = 30;
const COLUMN_WIDTH = 200;
const VIEW_STORAGE_KEY = 'cassyx.grid.view';

type ViewMode = 'table' | 'card';

/**
 * Virtualized result grid (plan §7).
 *
 * Server-side paging: the grid renders exactly the page it was given and moves between pages with
 * the opaque tokens on the result. It never accumulates the whole table in the browser, and there
 * is no client-side row cap standing in for one.
 */
export function DataGrid({
  result,
  columns: legacyColumns,
  rows: legacyRows,
  onNextPage,
  onPreviousPage,
  readOnlyReason,
  loading,
  connectionId,
  onRefresh,
}: DataGridProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const [view, setView] = useState<ViewMode>(readStoredView);
  const [search, setSearch] = useState('');
  const [sort, setSort] = useState<SortSpec | null>(null);
  const [blobFormat, setBlobFormat] = useState<'hex' | 'base64'>('hex');
  const [selected, setSelected] = useState<number | null>(null);
  const [editor, setEditor] = useState<RowEditorMode | null>(null);
  const [generated, setGenerated] = useState<string | null>(null);

  const columns: ColumnMetadata[] = useMemo(() => {
    if (result) return result.columns;
    return (legacyColumns ?? []).map((name) => ({ name, type: 'text' }) as ColumnMetadata);
  }, [legacyColumns, result]);

  const rawRows: ResultRow[] = useMemo(
    () => (result ? (result.rows as ResultRow[]) : (legacyRows ?? [])),
    [legacyRows, result],
  );

  const rows = useMemo(
    () => applyResultView(rawRows, columns, { search, sort }),
    [columns, rawRows, search, sort],
  );

  const columnDefs = useMemo<ColumnDef<ResultRow>[]>(
    () =>
      columns.map((column) => ({
        id: column.name,
        header: column.name,
        accessorFn: (row: ResultRow) => row[column.name],
      })),
    [columns],
  );

  // The source table is read from the RESULT's own column metadata, never from ambient UI state.
  // Resolving it from a selected tree node is exactly how the prior art ended up generating
  // `SELECT * FROM system_auth.users` after dropping `demo.users`.
  const source = useMemo(
    () => columns.find((column) => column.keyspace && column.table),
    [columns],
  );
  const canEdit = Boolean(connectionId && source?.keyspace && source.table && !readOnlyReason);
  const selectedRow = selected === null ? undefined : rows[selected];

  const generate = async (kind: StatementKind) => {
    if (!connectionId || !source?.keyspace || !source.table) return;
    const target = selectedRow ? [selectedRow] : rows;
    const result = await generateRowStatements(connectionId, source.keyspace, source.table, {
      statementKind: kind,
      rows: target as Record<string, unknown>[],
    });
    setGenerated(result.cql);
  };

  const table = useReactTable({
    data: rows,
    columns: columnDefs,
    getCoreRowModel: getCoreRowModel(),
  });

  const virtualizer = useVirtualizer({
    count: rows.length,
    getScrollElement: () => containerRef.current,
    estimateSize: () => ROW_HEIGHT,
    overscan: 16,
  });

  if (columns.length === 0) {
    return (
      <PanelPlaceholder
        title="Results"
        section="§7"
        workstream="C"
        testId="data-grid-empty"
        todo={[
          'Run a statement to see results here',
          'Paging is server-side via the driver PagingState — 500 rows per page, no client row cap',
        ]}
      >
        {result?.wasVoid && (
          <Alert severity="success" sx={{ mt: 1 }}>
            Statement executed. It returned no rows
            {result.applied === null || result.applied === undefined
              ? '.'
              : ` and [applied] = ${result.applied}.`}
          </Alert>
        )}
      </PanelPlaceholder>
    );
  }

  const virtualRows = virtualizer.getVirtualItems();

  return (
    <Stack sx={{ height: '100%', minHeight: 0 }} data-testid="data-grid">
      <Toolbar
        result={result ?? null}
        view={view}
        onView={(next) => {
          setView(next);
          storeView(next);
        }}
        search={search}
        onSearch={setSearch}
        blobFormat={blobFormat}
        onBlobFormat={setBlobFormat}
        onExportCsv={() => downloadCsv(toCsv(rows, columns))}
        canEdit={canEdit}
        hasSelection={selectedRow !== undefined}
        onInsert={() => setEditor('insert')}
        onUpdate={() => setEditor('update')}
        onDelete={() => setEditor('delete')}
        onGenerate={(kind) => void generate(kind)}
      />

      {readOnlyReason && (
        <Alert severity="info" variant="outlined" sx={{ m: 1 }} data-testid="grid-read-only">
          {readOnlyReason}
        </Alert>
      )}

      {result?.applied !== null && result?.applied !== undefined && (
        <Alert
          severity={result.applied ? 'success' : 'warning'}
          variant="outlined"
          sx={{ m: 1 }}
          data-testid="grid-applied"
        >
          Lightweight transaction <strong>[applied] = {String(result.applied)}</strong>
          {result.applied ? '' : ' — the condition was not met, so nothing was written.'}
        </Alert>
      )}

      {view === 'card' ? (
        <CardView rows={rows} columns={columns} blobFormat={blobFormat} />
      ) : (
        <Box
          ref={containerRef}
          sx={{ flex: 1, minHeight: 0, overflow: 'auto' }}
          data-testid="data-grid-scroll"
        >
          <Box
            sx={{ display: 'flex', position: 'sticky', top: 0, zIndex: 1, bgcolor: 'chrome.bar' }}
          >
            {table.getHeaderGroups()[0]?.headers.map((header) => {
              const column = columns.find((c) => c.name === header.id);
              return (
                <Box
                  key={header.id}
                  onClick={() => setSort((current) => nextSort(current, header.id))}
                  sx={{
                    flex: `0 0 ${COLUMN_WIDTH}px`,
                    px: 1,
                    py: 0.5,
                    fontWeight: 700,
                    fontSize: '0.75rem',
                    cursor: 'pointer',
                    borderRight: 1,
                    borderBottom: 1,
                    borderColor: 'chrome.border',
                    whiteSpace: 'nowrap',
                    overflow: 'hidden',
                    textOverflow: 'ellipsis',
                  }}
                >
                  {column?.primaryKeyColumn ? '🔑 ' : ''}
                  {header.id}
                  {sort?.column === header.id ? (sort.direction === 'asc' ? ' ▲' : ' ▼') : ''}
                  <Typography
                    component="span"
                    variant="caption"
                    color="text.secondary"
                    sx={{ ml: 0.5 }}
                  >
                    {column?.type}
                  </Typography>
                </Box>
              );
            })}
          </Box>

          <Box sx={{ height: virtualizer.getTotalSize(), position: 'relative' }}>
            {virtualRows.map((virtualRow) => {
              const row = rows[virtualRow.index];
              if (!row) return null;
              return (
                <Box
                  key={virtualRow.key}
                  data-testid="data-grid-row"
                  onClick={() => setSelected(virtualRow.index)}
                  sx={{
                    bgcolor: selected === virtualRow.index ? 'action.selected' : undefined,
                    position: 'absolute',
                    top: 0,
                    left: 0,
                    width: '100%',
                    height: ROW_HEIGHT,
                    transform: `translateY(${virtualRow.start}px)`,
                    display: 'flex',
                    alignItems: 'center',
                    '&:hover': { bgcolor: 'chrome.hover' },
                  }}
                >
                  {columns.map((column) => (
                    <Box
                      key={column.name}
                      sx={{
                        flex: `0 0 ${COLUMN_WIDTH}px`,
                        px: 1,
                        borderRight: 1,
                        borderBottom: 1,
                        borderColor: 'chrome.border',
                        height: '100%',
                        display: 'flex',
                        alignItems: 'center',
                        overflow: 'hidden',
                      }}
                    >
                      <DataGridCell
                        value={row[column.name]}
                        column={column}
                        present={column.name in row}
                        options={{ blobFormat, compact: true }}
                      />
                    </Box>
                  ))}
                </Box>
              );
            })}
          </Box>
        </Box>
      )}

      <Stack
        direction="row"
        spacing={1}
        alignItems="center"
        sx={{ px: 1, py: 0.5, borderTop: 1, borderColor: 'chrome.border' }}
      >
        <Typography variant="caption" color="text.secondary">
          {rows.length} of {rawRows.length} rows on page {result?.pageNumber ?? 1}
          {result?.elapsedMillis !== undefined ? ` · ${result.elapsedMillis} ms` : ''}
        </Typography>
        <Box sx={{ flex: 1 }} />
        <Tooltip title="Previous page — replays a paging token the server retained; Cassandra cannot page backwards on its own">
          <span>
            <IconButton
              size="small"
              data-testid="grid-previous-page"
              disabled={loading || !result?.previousPageToken || !onPreviousPage}
              onClick={onPreviousPage}
            >
              <ChevronLeftRoundedIcon fontSize="small" />
            </IconButton>
          </span>
        </Tooltip>
        <Tooltip title="Next page — server-side PagingState">
          <span>
            <IconButton
              size="small"
              data-testid="grid-next-page"
              disabled={loading || !result?.nextPageToken || !onNextPage}
              onClick={onNextPage}
            >
              <ChevronRightRoundedIcon fontSize="small" />
            </IconButton>
          </span>
        </Tooltip>
      </Stack>

      {generated && (
        <Alert
          severity="info"
          variant="outlined"
          sx={{ m: 1 }}
          data-testid="generated-statements"
          onClose={() => setGenerated(null)}
        >
          <Typography component="pre" variant="caption" sx={{ whiteSpace: 'pre-wrap', m: 0 }}>
            {generated}
          </Typography>
        </Alert>
      )}

      {editor && source?.keyspace && source.table && (
        <RowEditorDialog
          open
          mode={editor}
          connectionId={connectionId ?? null}
          keyspace={source.keyspace}
          table={source.table}
          columns={columns}
          row={editor === 'insert' ? undefined : selectedRow}
          onClose={() => setEditor(null)}
          onApplied={() => {
            setEditor(null);
            onRefresh?.();
          }}
        />
      )}
    </Stack>
  );
}

interface ToolbarProps {
  result: QueryResult | null;
  view: ViewMode;
  onView: (view: ViewMode) => void;
  search: string;
  onSearch: (search: string) => void;
  blobFormat: 'hex' | 'base64';
  onBlobFormat: (format: 'hex' | 'base64') => void;
  onExportCsv: () => void;
  canEdit: boolean;
  hasSelection: boolean;
  onInsert: () => void;
  onUpdate: () => void;
  onDelete: () => void;
  onGenerate: (kind: StatementKind) => void;
}

function Toolbar({
  result,
  view,
  onView,
  search,
  onSearch,
  blobFormat,
  onBlobFormat,
  onExportCsv,
  canEdit,
  hasSelection,
  onInsert,
  onUpdate,
  onDelete,
  onGenerate,
}: ToolbarProps) {
  return (
    <Stack
      direction="row"
      spacing={1}
      alignItems="center"
      sx={{ px: 1, py: 0.5, borderBottom: 1, borderColor: 'chrome.border' }}
    >
      <TextField
        size="small"
        placeholder="Filter this page"
        value={search}
        onChange={(event) => onSearch(event.target.value)}
        inputProps={{ 'aria-label': 'Filter results' }}
        InputProps={{
          startAdornment: (
            <InputAdornment position="start">
              <SearchRoundedIcon fontSize="small" />
            </InputAdornment>
          ),
        }}
        sx={{ width: 220 }}
      />
      <ToggleButtonGroup
        size="small"
        exclusive
        value={view}
        onChange={(_, next) => next && onView(next as ViewMode)}
      >
        <ToggleButton value="table" aria-label="Table view">
          <TableRowsRoundedIcon fontSize="small" />
        </ToggleButton>
        <ToggleButton value="card" aria-label="Card view">
          <ViewAgendaRoundedIcon fontSize="small" />
        </ToggleButton>
      </ToggleButtonGroup>
      <Divider orientation="vertical" flexItem />
      <ToggleButtonGroup
        size="small"
        exclusive
        value={blobFormat}
        onChange={(_, next) => next && onBlobFormat(next as 'hex' | 'base64')}
      >
        <ToggleButton value="hex" aria-label="Blobs as hex">
          hex
        </ToggleButton>
        <ToggleButton value="base64" aria-label="Blobs as base64">
          b64
        </ToggleButton>
      </ToggleButtonGroup>
      <Box sx={{ flex: 1 }} />
      {result?.warnings?.map((warning) => (
        <Tooltip key={warning} title={warning}>
          <Chip size="small" color="warning" variant="outlined" label="server warning" />
        </Tooltip>
      ))}
      {canEdit && (
        <>
          <Button size="small" onClick={onInsert} data-testid="row-insert">
            Insert row
          </Button>
          <Button size="small" disabled={!hasSelection} onClick={onUpdate} data-testid="row-update">
            Edit
          </Button>
          <Button
            size="small"
            color="error"
            disabled={!hasSelection}
            onClick={onDelete}
            data-testid="row-delete"
          >
            Delete
          </Button>
          <Tooltip title="Generate CQL for the selected row, or for the whole page when nothing is selected">
            <Button size="small" onClick={() => onGenerate('INSERT')} data-testid="row-generate">
              Generate INSERT
            </Button>
          </Tooltip>
        </>
      )}
      <Button size="small" onClick={onExportCsv}>
        Export page CSV
      </Button>
    </Stack>
  );
}

/** Card view — genuinely better than a table for the wide rows Cassandra encourages. */
function CardView({
  rows,
  columns,
  blobFormat,
}: {
  rows: ResultRow[];
  columns: ColumnMetadata[];
  blobFormat: 'hex' | 'base64';
}) {
  return (
    <Box sx={{ flex: 1, minHeight: 0, overflow: 'auto', p: 1 }} data-testid="data-grid-cards">
      {rows.map((row, index) => (
        <Box
          key={index}
          data-testid="data-grid-card"
          sx={{ border: 1, borderColor: 'chrome.border', borderRadius: 1, p: 1, mb: 1 }}
        >
          {columns.map((column) => (
            <Stack key={column.name} direction="row" spacing={1} sx={{ py: 0.25 }}>
              <Typography
                variant="caption"
                sx={{ width: 180, color: 'text.secondary', flexShrink: 0 }}
              >
                {column.primaryKeyColumn ? '🔑 ' : ''}
                {column.name}
              </Typography>
              <DataGridCell
                value={row[column.name]}
                column={column}
                present={column.name in row}
                options={{ blobFormat }}
              />
            </Stack>
          ))}
        </Box>
      ))}
    </Box>
  );
}

function readStoredView(): ViewMode {
  try {
    return window.localStorage.getItem(VIEW_STORAGE_KEY) === 'card' ? 'card' : 'table';
  } catch {
    return 'table';
  }
}

function storeView(view: ViewMode): void {
  try {
    window.localStorage.setItem(VIEW_STORAGE_KEY, view);
  } catch {
    // Private-browsing mode; the preference simply does not persist.
  }
}

function downloadCsv(csv: string): void {
  const blob = new Blob([csv], { type: 'text/csv' });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = 'cassyx-result-page.csv';
  anchor.click();
  URL.revokeObjectURL(url);
}
