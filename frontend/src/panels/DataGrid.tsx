import { useMemo, useRef } from 'react';
import Box from '@mui/material/Box';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { flexRender, getCoreRowModel, useReactTable, type ColumnDef } from '@tanstack/react-table';
import { useVirtualizer } from '@tanstack/react-virtual';
import { PanelPlaceholder } from './PanelPlaceholder';

export interface DataGridRow {
  [column: string]: unknown;
}

export interface DataGridProps {
  columns: string[];
  rows: DataGridRow[];
}

const ROW_HEIGHT = 28;

/**
 * Virtualized result grid (plan §7). SHELL ONLY — the wiring, not the features.
 *
 * Proven here: TanStack Table + TanStack Virtual over a fixed row height, which is what has to
 * hold up on a 1000-column-wide table (§11.2 benchmark: first paint < 1s).
 *
 * Phase 1 workstream G adds: type-aware renderers (collections, UDTs, tuples, blob hex/base64,
 * timeuuid with decoded timestamp, vectors as sparklines), inline editing that generates
 * `UPDATE … WHERE <full primary key>` and refuses result sets that do not project the complete
 * primary key, row CRUD, card view, and the null-vs-unset distinction.
 */
export function DataGrid({ columns, rows }: DataGridProps) {
  const containerRef = useRef<HTMLDivElement>(null);

  const columnDefs = useMemo<ColumnDef<DataGridRow>[]>(
    () =>
      columns.map((column) => ({
        id: column,
        header: column,
        accessorFn: (row: DataGridRow) => row[column],
        cell: (info) => formatCell(info.getValue()),
      })),
    [columns],
  );

  const table = useReactTable({
    data: rows,
    columns: columnDefs,
    getCoreRowModel: getCoreRowModel(),
  });

  const virtualizer = useVirtualizer({
    count: rows.length,
    getScrollElement: () => containerRef.current,
    estimateSize: () => ROW_HEIGHT,
    overscan: 12,
  });

  if (columns.length === 0) {
    return (
      <PanelPlaceholder
        title="Results"
        section="§7"
        workstream="G"
        testId="data-grid-empty"
        todo={[
          'Type-aware renderers and editors for every CQL type, including vectors',
          'Inline edit → UPDATE with the full primary key, previewed before execution',
          'Row CRUD, card view, client-side filter/sort/search, null vs unset',
        ]}
      >
        <Typography variant="body2" color="text.secondary">
          Execute a statement to see results here. Paging is server-side via the driver&apos;s
          PagingState — 500 rows per page, with no client-side row cap.
        </Typography>
      </PanelPlaceholder>
    );
  }

  const virtualRows = virtualizer.getVirtualItems();

  return (
    <Stack sx={{ height: '100%', minHeight: 0 }} data-testid="data-grid">
      <Box
        ref={containerRef}
        sx={{
          flex: 1,
          minHeight: 0,
          overflow: 'auto',
          fontFamily: 'monospace',
          fontSize: '0.8rem',
        }}
      >
        <Box sx={{ display: 'flex', position: 'sticky', top: 0, zIndex: 1, bgcolor: 'chrome.bar' }}>
          {table.getHeaderGroups()[0]?.headers.map((header) => (
            <Box
              key={header.id}
              sx={{
                flex: '0 0 180px',
                px: 1,
                py: 0.5,
                fontWeight: 700,
                borderRight: 1,
                borderBottom: 1,
                borderColor: 'chrome.border',
                whiteSpace: 'nowrap',
                overflow: 'hidden',
                textOverflow: 'ellipsis',
              }}
            >
              {flexRender(header.column.columnDef.header, header.getContext())}
            </Box>
          ))}
        </Box>

        <Box sx={{ height: virtualizer.getTotalSize(), position: 'relative' }}>
          {virtualRows.map((virtualRow) => {
            const row = table.getRowModel().rows[virtualRow.index];
            if (!row) return null;
            return (
              <Box
                key={row.id}
                data-testid="data-grid-row"
                sx={{
                  position: 'absolute',
                  top: 0,
                  left: 0,
                  width: '100%',
                  height: ROW_HEIGHT,
                  transform: `translateY(${virtualRow.start}px)`,
                  display: 'flex',
                  '&:hover': { bgcolor: 'chrome.hover' },
                }}
              >
                {row.getVisibleCells().map((cell) => (
                  <Box
                    key={cell.id}
                    sx={{
                      flex: '0 0 180px',
                      px: 1,
                      py: 0.25,
                      borderRight: 1,
                      borderBottom: 1,
                      borderColor: 'chrome.border',
                      whiteSpace: 'nowrap',
                      overflow: 'hidden',
                      textOverflow: 'ellipsis',
                    }}
                  >
                    {flexRender(cell.column.columnDef.cell, cell.getContext())}
                  </Box>
                ))}
              </Box>
            );
          })}
        </Box>
      </Box>
      <Box sx={{ px: 1, py: 0.25, borderTop: 1, borderColor: 'chrome.border' }}>
        <Typography variant="caption" color="text.secondary">
          {rows.length} rows · virtualized
        </Typography>
      </Box>
    </Stack>
  );
}

function formatCell(value: unknown): string {
  if (value === null) return 'null';
  if (value === undefined) return '';
  if (typeof value === 'object') return JSON.stringify(value);
  return String(value);
}
