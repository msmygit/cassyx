import { useCallback, useMemo, type DragEvent } from 'react';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Chip from '@mui/material/Chip';
import Divider from '@mui/material/Divider';
import Stack from '@mui/material/Stack';
import Tooltip from '@mui/material/Tooltip';
import Typography from '@mui/material/Typography';
import PlayArrowRoundedIcon from '@mui/icons-material/PlayArrowRounded';
import StopRoundedIcon from '@mui/icons-material/StopRounded';
import CodeMirror from '@uiw/react-codemirror';
import { sql, StandardSQL } from '@codemirror/lang-sql';
import { EditorView } from '@codemirror/view';
import { useColorMode } from '../theme/colorMode';
import { parseDragPayload, SCHEMA_DRAG_MIME } from '../schema/model';

export interface QueryEditorProps {
  value: string;
  onChange: (value: string) => void;
  /** Wired up by Phase 1 workstream C. */
  onExecute?: (statement: string) => void;
  onCancel?: () => void;
  running?: boolean;
}

/**
 * CQL editor (plan §5.1). SHELL ONLY.
 *
 * Present: CodeMirror 6, light/dark following the app theme, and the drop target for schema-tree
 * drags — which inserts the statement carried on the DRAGGED NODE's own payload, never one
 * rebuilt from ambient state. That is the prior-art `system_auth.users` bug closed at the
 * receiving end as well as the sending end.
 *
 * Phase 1 workstream C adds: a real CQL lexer for statement splitting (not `split(';')` — string
 * literals and UDF bodies contain semicolons), execute-all / statement-under-cursor / selection,
 * consistency-level and tracing controls, BATCH builder, LWT handling, and cancellation.
 */
export function QueryEditor({ value, onChange, onExecute, onCancel, running }: QueryEditorProps) {
  const { mode } = useColorMode();

  const extensions = useMemo(
    () => [sql({ dialect: StandardSQL, upperCaseKeywords: true }), EditorView.lineWrapping],
    [],
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
        sx={{ px: 1, py: 0.5, borderBottom: 1, borderColor: 'chrome.border' }}
      >
        <Button
          size="small"
          variant="contained"
          startIcon={<PlayArrowRoundedIcon />}
          disabled={running || !value.trim()}
          onClick={() => onExecute?.(value)}
        >
          Execute
        </Button>
        {running && (
          <Button size="small" color="error" startIcon={<StopRoundedIcon />} onClick={onCancel}>
            Cancel
          </Button>
        )}
        <Divider orientation="vertical" flexItem />
        <Tooltip title="Server-side paging via the driver's PagingState — no hardcoded LIMIT (plan §5.1)">
          <Chip size="small" variant="outlined" label="fetch size 500" />
        </Tooltip>
        <Chip size="small" variant="outlined" label="LOCAL_ONE" />
        <Box sx={{ flex: 1 }} />
        <Typography variant="caption" color="text.secondary">
          Drag a table from the schema tree to insert a SELECT
        </Typography>
      </Stack>

      <Box sx={{ flex: 1, minHeight: 0, overflow: 'auto' }}>
        <CodeMirror
          value={value}
          height="100%"
          theme={mode}
          extensions={extensions}
          onChange={onChange}
          basicSetup={{ lineNumbers: true, foldGutter: true, highlightActiveLine: true }}
        />
      </Box>
    </Box>
  );
}
