import { useMemo, useState } from 'react';
import Alert from '@mui/material/Alert';
import Button from '@mui/material/Button';
import Dialog from '@mui/material/Dialog';
import DialogActions from '@mui/material/DialogActions';
import DialogContent from '@mui/material/DialogContent';
import DialogTitle from '@mui/material/DialogTitle';
import IconButton from '@mui/material/IconButton';
import Stack from '@mui/material/Stack';
import TextField from '@mui/material/TextField';
import Tooltip from '@mui/material/Tooltip';
import Typography from '@mui/material/Typography';
import BlockRoundedIcon from '@mui/icons-material/BlockRounded';
import ClearRoundedIcon from '@mui/icons-material/ClearRounded';
import { deleteRow, insertRow, updateRow } from '../query/api';
import { parseEditorValue, renderValue } from '../query/cqlValue';
import { primaryKeyOf } from '../query/resultView';
import { UNSET, type ColumnMetadata, type ResultRow, type RowMutationResult } from '../query/types';

export type RowEditorMode = 'insert' | 'update' | 'delete';

export interface RowEditorDialogProps {
  open: boolean;
  mode: RowEditorMode;
  connectionId: string | null;
  keyspace: string;
  table: string;
  columns: ColumnMetadata[];
  /** The row being edited, for `update` / `delete`. */
  row?: ResultRow;
  onClose: () => void;
  onApplied?: (result: RowMutationResult) => void;
}

/**
 * Row insert / update / delete with TTL and timestamp (plan §7).
 *
 * Two things this does that most Cassandra GUIs do not:
 *
 * 1. **`null` and *unset* are separate, explicit choices per field.** A null writes a tombstone; an
 *    unset column is not written at all. Collapsing them, as an empty text box would, silently
 *    changes what the statement does.
 * 2. **The generated statement is previewed before it runs.** `previewOnly` round-trips to the
 *    server, so what you approve is exactly what executes — not a client-side guess at it.
 */
export function RowEditorDialog({
  open,
  mode,
  connectionId,
  keyspace,
  table,
  columns,
  row,
  onClose,
  onApplied,
}: RowEditorDialogProps) {
  const [values, setValues] = useState<Record<string, unknown>>(() => seed(columns, row, mode));
  const [ttlSeconds, setTtlSeconds] = useState('');
  const [timestampMicros, setTimestampMicros] = useState('');
  const [preview, setPreview] = useState<RowMutationResult | null>(null);
  const [error, setError] = useState<string | null>(null);

  const keyResult = useMemo(
    () => (row ? primaryKeyOf(row, columns) : { missing: [] }),
    [columns, row],
  );
  const missingKey = 'missing' in keyResult ? keyResult.missing : [];

  const call = async (previewOnly: boolean) => {
    if (!connectionId) {
      setError('Connect to a cluster first.');
      return;
    }
    setError(null);
    const common = {
      ...(ttlSeconds ? { ttlSeconds: Number(ttlSeconds) } : {}),
      ...(timestampMicros ? { timestampMicros: Number(timestampMicros) } : {}),
      previewOnly,
    };
    try {
      let result: RowMutationResult;
      if (mode === 'insert') {
        result = await insertRow(connectionId, keyspace, table, { values, ...common });
      } else if (mode === 'update') {
        const primaryKey = 'key' in keyResult ? keyResult.key : {};
        const editable = Object.fromEntries(
          Object.entries(values).filter(([name]) => !(name in primaryKey)),
        );
        result = await updateRow(connectionId, keyspace, table, {
          primaryKey,
          values: editable,
          ...common,
        });
      } else {
        const primaryKey = 'key' in keyResult ? keyResult.key : {};
        result = await deleteRow(connectionId, keyspace, table, { primaryKey, ...common });
      }
      setPreview(result);
      if (!previewOnly) onApplied?.(result);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  return (
    <Dialog open={open} onClose={onClose} maxWidth="md" fullWidth data-testid="row-editor">
      <DialogTitle>
        {mode === 'insert' ? 'Insert row' : mode === 'update' ? 'Update row' : 'Delete row'} ·{' '}
        {keyspace}.{table}
      </DialogTitle>
      <DialogContent>
        {missingKey.length > 0 && (
          <Alert severity="warning" sx={{ mb: 2 }} data-testid="row-editor-missing-key">
            This row does not carry {missingKey.join(', ')}, part of the primary key, so it cannot
            be identified for {mode}. Re-run the query projecting the full primary key.
          </Alert>
        )}

        {mode !== 'delete' &&
          columns.map((column) => {
            const value = values[column.name];
            const unset = value === UNSET;
            return (
              <Stack
                key={column.name}
                direction="row"
                spacing={1}
                alignItems="center"
                sx={{ mb: 1 }}
              >
                <TextField
                  fullWidth
                  size="small"
                  label={`${column.name} · ${column.type}`}
                  value={unset || value === null ? '' : renderValue(value, column)}
                  placeholder={
                    unset ? '⊘ unset — column will not be written' : 'null — writes a tombstone'
                  }
                  disabled={unset || value === null}
                  onChange={(event) => {
                    try {
                      setValues((current) => ({
                        ...current,
                        [column.name]: parseEditorValue(event.target.value, column),
                      }));
                      setError(null);
                    } catch (e) {
                      setError(e instanceof Error ? e.message : String(e));
                    }
                  }}
                />
                <Tooltip title="null — writes a tombstone">
                  <IconButton
                    size="small"
                    color={value === null ? 'warning' : 'default'}
                    aria-label={`Set ${column.name} to null`}
                    onClick={() => setValues((current) => ({ ...current, [column.name]: null }))}
                  >
                    <ClearRoundedIcon fontSize="small" />
                  </IconButton>
                </Tooltip>
                <Tooltip title="unset — the column is left out of the statement entirely">
                  <IconButton
                    size="small"
                    color={unset ? 'primary' : 'default'}
                    aria-label={`Leave ${column.name} unset`}
                    onClick={() => setValues((current) => ({ ...current, [column.name]: UNSET }))}
                  >
                    <BlockRoundedIcon fontSize="small" />
                  </IconButton>
                </Tooltip>
              </Stack>
            );
          })}

        <Stack direction="row" spacing={1} sx={{ mt: 1 }}>
          <TextField
            size="small"
            label="TTL (seconds)"
            value={ttlSeconds}
            onChange={(event) => setTtlSeconds(event.target.value)}
          />
          <TextField
            size="small"
            label="Timestamp (µs)"
            value={timestampMicros}
            onChange={(event) => setTimestampMicros(event.target.value)}
            sx={{ flex: 1 }}
          />
        </Stack>

        {error && (
          <Alert severity="error" sx={{ mt: 2 }}>
            {error}
          </Alert>
        )}

        {preview && (
          <>
            <Typography variant="overline" color="text.secondary" sx={{ mt: 2, display: 'block' }}>
              {preview.executed ? 'Executed' : 'Preview'}
              {preview.applied === null || preview.applied === undefined
                ? ''
                : ` · [applied] = ${preview.applied}`}
            </Typography>
            <Typography
              component="pre"
              variant="caption"
              data-testid="row-editor-preview"
              sx={{ p: 1, bgcolor: 'chrome.bar', borderRadius: 1, overflow: 'auto' }}
            >
              {preview.cql}
            </Typography>
          </>
        )}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button onClick={() => void call(true)} data-testid="row-editor-preview-button">
          Preview CQL
        </Button>
        <Button
          variant="contained"
          color={mode === 'delete' ? 'error' : 'primary'}
          disabled={mode !== 'insert' && missingKey.length > 0}
          onClick={() => void call(false)}
          data-testid="row-editor-apply"
        >
          {mode === 'delete' ? 'Delete' : 'Apply'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

/** Seeds the form: an insert starts every column unset, an update starts from the row. */
function seed(
  columns: ColumnMetadata[],
  row: ResultRow | undefined,
  mode: RowEditorMode,
): Record<string, unknown> {
  const values: Record<string, unknown> = {};
  columns.forEach((column) => {
    if (mode === 'insert' || !row) {
      values[column.name] = UNSET;
    } else {
      values[column.name] = column.name in row ? row[column.name] : UNSET;
    }
  });
  return values;
}
