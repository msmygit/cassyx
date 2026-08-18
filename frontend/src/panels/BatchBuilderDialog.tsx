import { useState } from 'react';
import Alert from '@mui/material/Alert';
import Button from '@mui/material/Button';
import Dialog from '@mui/material/Dialog';
import DialogActions from '@mui/material/DialogActions';
import DialogContent from '@mui/material/DialogContent';
import DialogTitle from '@mui/material/DialogTitle';
import IconButton from '@mui/material/IconButton';
import MenuItem from '@mui/material/MenuItem';
import Stack from '@mui/material/Stack';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import AddRoundedIcon from '@mui/icons-material/AddRounded';
import DeleteOutlineRoundedIcon from '@mui/icons-material/DeleteOutlineRounded';
import { executeBatch } from '../query/api';
import {
  BATCH_TYPES,
  CONSISTENCY_LEVELS,
  type BatchResult,
  type BatchType,
  type ConsistencyLevel,
} from '../query/types';

export interface BatchBuilderDialogProps {
  open: boolean;
  connectionId: string | null;
  /** Seed statements, e.g. the statements the lexer found in the editor. */
  initialStatements?: string[];
  onClose: () => void;
  /** Called with the assembled CQL so the caller can drop it into the editor. */
  onAssembled?: (cql: string) => void;
}

/**
 * BATCH builder (plan §5.1).
 *
 * Preview runs first and always: the server assembles the statement, analyses whether it spans
 * partitions and returns the warning WITHOUT executing. A multi-partition batch costs the
 * coordinator far more than the same writes issued in parallel, and it is the single most common
 * Cassandra anti-pattern a GUI can help you avoid — so you see the verdict before you commit.
 */
export function BatchBuilderDialog({
  open,
  connectionId,
  initialStatements = [],
  onClose,
  onAssembled,
}: BatchBuilderDialogProps) {
  const [type, setType] = useState<BatchType>('LOGGED');
  const [consistency, setConsistency] = useState<ConsistencyLevel>('LOCAL_QUORUM');
  const [statements, setStatements] = useState<string[]>(
    initialStatements.length > 0 ? initialStatements : [''],
  );
  const [preview, setPreview] = useState<BatchResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const call = async (previewOnly: boolean) => {
    if (!connectionId) {
      setError('Connect to a cluster first.');
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const result = await executeBatch(connectionId, {
        type,
        consistency,
        previewOnly,
        statements: statements.filter((cql) => cql.trim()).map((cql) => ({ cql })),
      });
      setPreview(result);
      if (!previewOnly) onAssembled?.(result.assembledCql);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog open={open} onClose={onClose} maxWidth="md" fullWidth data-testid="batch-builder">
      <DialogTitle>BATCH builder</DialogTitle>
      <DialogContent>
        <Stack direction="row" spacing={1} sx={{ mb: 2, mt: 1 }}>
          <TextField
            select
            size="small"
            label="Type"
            value={type}
            onChange={(event) => setType(event.target.value as BatchType)}
            sx={{ width: 160 }}
          >
            {BATCH_TYPES.map((value) => (
              <MenuItem key={value} value={value}>
                {value}
              </MenuItem>
            ))}
          </TextField>
          <TextField
            select
            size="small"
            label="Consistency"
            value={consistency}
            onChange={(event) => setConsistency(event.target.value as ConsistencyLevel)}
            sx={{ width: 180 }}
          >
            {CONSISTENCY_LEVELS.map((value) => (
              <MenuItem key={value} value={value}>
                {value}
              </MenuItem>
            ))}
          </TextField>
        </Stack>

        {statements.map((statement, index) => (
          <Stack key={index} direction="row" spacing={1} alignItems="flex-start" sx={{ mb: 1 }}>
            <TextField
              fullWidth
              multiline
              minRows={2}
              size="small"
              label={`Statement ${index + 1}`}
              value={statement}
              onChange={(event) =>
                setStatements((current) =>
                  current.map((s, i) => (i === index ? event.target.value : s)),
                )
              }
            />
            <IconButton
              size="small"
              aria-label={`Remove statement ${index + 1}`}
              onClick={() => setStatements((current) => current.filter((_, i) => i !== index))}
            >
              <DeleteOutlineRoundedIcon fontSize="small" />
            </IconButton>
          </Stack>
        ))}

        <Button
          size="small"
          startIcon={<AddRoundedIcon />}
          onClick={() => setStatements((current) => [...current, ''])}
        >
          Add statement
        </Button>

        {error && (
          <Alert severity="error" sx={{ mt: 2 }}>
            {error}
          </Alert>
        )}

        {preview && (
          <>
            {preview.spansMultiplePartitions && (
              <Alert severity="warning" sx={{ mt: 2 }} data-testid="batch-partition-warning">
                This batch spans {preview.distinctPartitions} partitions. A multi-partition batch is
                not an optimisation — the coordinator has to fan out and wait, which is slower than
                issuing the same writes in parallel, and a LOGGED batch also writes to the batchlog
                twice.
              </Alert>
            )}
            {preview.warnings?.map((warning) => (
              <Alert key={warning} severity="info" sx={{ mt: 1 }}>
                {warning}
              </Alert>
            ))}
            <Typography
              component="pre"
              variant="caption"
              sx={{ mt: 2, p: 1, bgcolor: 'chrome.bar', borderRadius: 1, overflow: 'auto' }}
            >
              {preview.assembledCql}
            </Typography>
          </>
        )}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Close</Button>
        <Button onClick={() => call(true)} disabled={busy} data-testid="batch-preview">
          Preview
        </Button>
        <Button
          variant="contained"
          onClick={() => call(false)}
          disabled={busy}
          data-testid="batch-execute"
        >
          Execute
        </Button>
      </DialogActions>
    </Dialog>
  );
}
