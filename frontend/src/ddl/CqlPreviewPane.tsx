import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import CircularProgress from '@mui/material/CircularProgress';
import Stack from '@mui/material/Stack';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';

export interface CqlPreviewPaneProps {
  /** Generated CQL from `POST /ddl/generate`, or the user's edited version of it. */
  cql: string;
  onCqlChange: (cql: string) => void;
  warnings?: string[];
  /** Problem detail from a failed generate, shown instead of stale CQL. */
  error?: string | null;
  loading?: boolean;
  /** Disabled while the form is incomplete; the reasons are listed above the pane. */
  problems?: string[];
  onExecute: () => void;
  executing?: boolean;
  executeLabel?: string;
}

/**
 * The Preview CQL pane every visual editor must show (plan §4).
 *
 * Two rules it exists to enforce:
 *
 * - The generated statement is **always shown**. There is no code path from an editor to the
 *   cluster that does not render through here first.
 * - It is **always editable**. The textarea is the thing that gets executed, so a user who knows
 *   CQL better than our form does is never blocked by the form.
 */
export function CqlPreviewPane({
  cql,
  onCqlChange,
  warnings = [],
  error = null,
  loading = false,
  problems = [],
  onExecute,
  executing = false,
  executeLabel = 'Execute',
}: CqlPreviewPaneProps) {
  const blocked = problems.length > 0 || cql.trim().length === 0;

  return (
    <Stack spacing={1} sx={{ minHeight: 0 }} data-testid="cql-preview-pane">
      <Stack direction="row" alignItems="center" spacing={1}>
        <Typography variant="subtitle2">Preview CQL</Typography>
        {loading && <CircularProgress size={14} aria-label="Generating CQL" />}
        <Typography variant="caption" color="text.secondary">
          Editable — what you see here is exactly what runs.
        </Typography>
      </Stack>

      {problems.map((problem) => (
        <Alert key={problem} severity="info" variant="outlined">
          {problem}
        </Alert>
      ))}

      {error && (
        <Alert severity="error" variant="outlined" data-testid="cql-preview-error">
          {error}
        </Alert>
      )}

      {warnings.map((warning) => (
        <Alert key={warning} severity="warning" variant="outlined">
          {warning}
        </Alert>
      ))}

      <TextField
        multiline
        minRows={6}
        maxRows={18}
        fullWidth
        value={cql}
        onChange={(event) => onCqlChange(event.target.value)}
        slotProps={{
          htmlInput: {
            'aria-label': 'Generated CQL',
            'data-testid': 'cql-preview',
            spellCheck: false,
          },
        }}
        sx={{ '& textarea': { fontFamily: 'monospace', fontSize: '0.8rem' } }}
      />

      <Box sx={{ display: 'flex', justifyContent: 'flex-end' }}>
        <Button
          variant="contained"
          size="small"
          onClick={onExecute}
          disabled={blocked || executing}
          data-testid="cql-execute"
        >
          {executing ? 'Running…' : executeLabel}
        </Button>
      </Box>
    </Stack>
  );
}
