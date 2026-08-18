import { useCallback, useMemo, useRef, useState, type DragEvent } from 'react';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import ButtonGroup from '@mui/material/ButtonGroup';
import Checkbox from '@mui/material/Checkbox';
import Divider from '@mui/material/Divider';
import FormControlLabel from '@mui/material/FormControlLabel';
import MenuItem from '@mui/material/MenuItem';
import Stack from '@mui/material/Stack';
import TextField from '@mui/material/TextField';
import Tooltip from '@mui/material/Tooltip';
import Typography from '@mui/material/Typography';
import PlayArrowRoundedIcon from '@mui/icons-material/PlayArrowRounded';
import StopRoundedIcon from '@mui/icons-material/StopRounded';
import CodeMirror, { type ReactCodeMirrorRef } from '@uiw/react-codemirror';
import { useColorMode } from '../theme/colorMode';
import { parseDragPayload, SCHEMA_DRAG_MIME } from '../schema/model';
import { cqlExtensions, type CqlCompletionSchema } from '../query/cqlLanguage';
import {
  CONSISTENCY_LEVELS,
  DEFAULT_FETCH_SIZE,
  SERIAL_CONSISTENCY_LEVELS,
  type ConsistencyLevel,
  type SerialConsistencyLevel,
} from '../query/types';
import type { ScriptRunMode } from '../query/useQueryRunner';

/* eslint-disable react-refresh/only-export-components -- the options value object belongs with the
   control that edits it; splitting a 6-field record into its own module to satisfy Fast Refresh
   would make the editor harder to read for no runtime benefit. */

/** Statement-level controls of plan §5.1, as one value object. */
export interface StatementOptions {
  consistency: ConsistencyLevel;
  serialConsistency?: SerialConsistencyLevel;
  fetchSize: number;
  timeoutMillis?: number;
  tracing: boolean;
  idempotent: boolean;
}

export const DEFAULT_STATEMENT_OPTIONS: StatementOptions = {
  consistency: 'LOCAL_ONE',
  fetchSize: DEFAULT_FETCH_SIZE,
  tracing: false,
  idempotent: false,
};

export interface QueryEditorProps {
  value: string;
  onChange: (value: string) => void;
  /** `mode` distinguishes execute-all from statement-under-cursor from selection. */
  onExecute?: (statement: string, mode: ScriptRunMode, cursorOffset: number) => void;
  onCancel?: () => void;
  running?: boolean;
  options?: StatementOptions;
  onOptionsChange?: (options: StatementOptions) => void;
  /** `keyspace.table` → columns, for autocomplete. */
  completionSchema?: CqlCompletionSchema;
  defaultKeyspace?: string;
  onOpenBatchBuilder?: () => void;
}

/**
 * CQL editor (plan §5.1).
 *
 * Three run modes, because a script pane with only "run everything" is useless for the way people
 * actually work: **execute all**, **statement under cursor**, and **selection**. Which statements
 * those are is decided by the server's CQL lexer, never by `split(';')`.
 */
export function QueryEditor({
  value,
  onChange,
  onExecute,
  onCancel,
  running,
  options = DEFAULT_STATEMENT_OPTIONS,
  onOptionsChange,
  completionSchema,
  defaultKeyspace,
  onOpenBatchBuilder,
}: QueryEditorProps) {
  const { mode } = useColorMode();
  const editorRef = useRef<ReactCodeMirrorRef>(null);
  const [cursorOffset, setCursorOffset] = useState(0);

  const extensions = useMemo(
    () => cqlExtensions({ schema: completionSchema, defaultSchema: defaultKeyspace }),
    [completionSchema, defaultKeyspace],
  );

  const selection = useCallback((): string => {
    const view = editorRef.current?.view;
    if (!view) return '';
    const { from, to } = view.state.selection.main;
    return from === to ? '' : view.state.sliceDoc(from, to);
  }, []);

  const execute = useCallback(
    (runMode: ScriptRunMode) => {
      const selected = selection();
      if (runMode === 'selection' && selected) {
        onExecute?.(selected, 'selection', 0);
        return;
      }
      onExecute?.(value, runMode, cursorOffset);
    },
    [cursorOffset, onExecute, selection, value],
  );

  const handleDrop = useCallback(
    (event: DragEvent<HTMLDivElement>) => {
      const raw = event.dataTransfer.getData(SCHEMA_DRAG_MIME);
      if (!raw) return;
      event.preventDefault();
      const payload = parseDragPayload(raw);
      if (!payload) return;
      // Use the statement from the payload: it was built from the dragged node's own identity.
      onChange(
        value ? `${value.replace(/\s*$/, '')}\n${payload.statement}\n` : `${payload.statement}\n`,
      );
    },
    [onChange, value],
  );

  const update = (patch: Partial<StatementOptions>) => onOptionsChange?.({ ...options, ...patch });

  return (
    <Box
      sx={{ height: '100%', minHeight: 0, display: 'flex', flexDirection: 'column' }}
      data-testid="query-editor"
      onDragOver={(event) => {
        if (event.dataTransfer.types.includes(SCHEMA_DRAG_MIME)) event.preventDefault();
      }}
      onDrop={handleDrop}
    >
      <Stack
        direction="row"
        spacing={1}
        alignItems="center"
        flexWrap="wrap"
        useFlexGap
        sx={{ px: 1, py: 0.5, borderBottom: 1, borderColor: 'chrome.border' }}
      >
        <ButtonGroup size="small" variant="contained" disabled={running || !value.trim()}>
          <Tooltip title="Execute every statement in the script">
            <span>
              <Button
                startIcon={<PlayArrowRoundedIcon />}
                onClick={() => execute('all')}
                disabled={running || !value.trim()}
                data-testid="execute-all"
              >
                Execute
              </Button>
            </span>
          </Tooltip>
          <Tooltip title="Execute only the statement containing the caret (lexer-resolved, not split on ';')">
            <span>
              <Button
                onClick={() => execute('cursor')}
                disabled={running || !value.trim()}
                data-testid="execute-cursor"
              >
                Statement
              </Button>
            </span>
          </Tooltip>
          <Tooltip title="Execute the current selection">
            <span>
              <Button
                onClick={() => execute('selection')}
                disabled={running || !value.trim()}
                data-testid="execute-selection"
              >
                Selection
              </Button>
            </span>
          </Tooltip>
        </ButtonGroup>

        {running && (
          <Button
            size="small"
            color="error"
            startIcon={<StopRoundedIcon />}
            onClick={onCancel}
            data-testid="cancel-query"
          >
            Cancel
          </Button>
        )}

        <Divider orientation="vertical" flexItem />

        <TextField
          select
          size="small"
          label="Consistency"
          value={options.consistency}
          onChange={(event) => update({ consistency: event.target.value as ConsistencyLevel })}
          sx={{ width: 150 }}
        >
          {CONSISTENCY_LEVELS.map((level) => (
            <MenuItem key={level} value={level}>
              {level}
            </MenuItem>
          ))}
        </TextField>

        <Tooltip title="Paxos-phase consistency; applies to lightweight transactions only">
          <TextField
            select
            size="small"
            label="Serial"
            value={options.serialConsistency ?? ''}
            onChange={(event) =>
              update({
                serialConsistency: (event.target.value || undefined) as SerialConsistencyLevel,
              })
            }
            sx={{ width: 140 }}
          >
            <MenuItem value="">default</MenuItem>
            {SERIAL_CONSISTENCY_LEVELS.map((level) => (
              <MenuItem key={level} value={level}>
                {level}
              </MenuItem>
            ))}
          </TextField>
        </Tooltip>

        <Tooltip title="Rows per page. Paging is server-side via the driver's PagingState — there is no hidden LIMIT.">
          <TextField
            size="small"
            type="number"
            label="Fetch size"
            value={options.fetchSize}
            onChange={(event) =>
              update({ fetchSize: Number(event.target.value) || DEFAULT_FETCH_SIZE })
            }
            sx={{ width: 110 }}
          />
        </Tooltip>

        <TextField
          size="small"
          type="number"
          label="Timeout ms"
          value={options.timeoutMillis ?? ''}
          onChange={(event) =>
            update({ timeoutMillis: event.target.value ? Number(event.target.value) : undefined })
          }
          sx={{ width: 120 }}
        />

        <Tooltip title="TRACING ON — renders the full system_traces timeline for the statement">
          <FormControlLabel
            control={
              <Checkbox
                size="small"
                checked={options.tracing}
                onChange={(event) => update({ tracing: event.target.checked })}
                data-testid="tracing-toggle"
              />
            }
            label={<Typography variant="caption">Trace</Typography>}
          />
        </Tooltip>

        <Tooltip title="Marks the statement safe for speculative execution and retries">
          <FormControlLabel
            control={
              <Checkbox
                size="small"
                checked={options.idempotent}
                onChange={(event) => update({ idempotent: event.target.checked })}
              />
            }
            label={<Typography variant="caption">Idempotent</Typography>}
          />
        </Tooltip>

        {onOpenBatchBuilder && (
          <Button size="small" onClick={onOpenBatchBuilder} data-testid="open-batch-builder">
            BATCH…
          </Button>
        )}
      </Stack>

      <Box sx={{ flex: 1, minHeight: 0, overflow: 'auto' }}>
        <CodeMirror
          ref={editorRef}
          value={value}
          height="100%"
          theme={mode}
          extensions={extensions}
          onChange={onChange}
          onUpdate={(update) => setCursorOffset(update.state.selection.main.head)}
          basicSetup={{
            lineNumbers: true,
            foldGutter: true,
            highlightActiveLine: true,
            autocompletion: true,
          }}
        />
      </Box>
    </Box>
  );
}
