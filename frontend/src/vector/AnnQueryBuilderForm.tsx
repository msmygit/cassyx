import { useMemo, useRef, useState } from 'react';
import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Chip from '@mui/material/Chip';
import FormControlLabel from '@mui/material/FormControlLabel';
import MenuItem from '@mui/material/MenuItem';
import Stack from '@mui/material/Stack';
import Switch from '@mui/material/Switch';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import {
  PREDICATE_OPERATORS,
  SIMILARITY_FUNCTIONS,
  type AnnPredicate,
  type AnnQueryPreview,
  type AnnQueryRequest,
  type PredicateOperator,
  type SimilarityFunction,
  type VectorColumn,
} from './types';
import {
  annBuilderProblem,
  buildAnnRequest,
  emptyBuilderState,
  parseVectorText,
  VectorParseError,
  type AnnBuilderState,
} from './vectorModel';

export interface AnnQueryBuilderFormProps {
  keyspace: string;
  table: string;
  columns: readonly VectorColumn[];
  /** Preview is generate-only; nothing runs until the user presses Run. */
  onPreview?: (request: AnnQueryRequest) => void;
  onRun?: (request: AnnQueryRequest) => void;
  preview?: AnnQueryPreview | null;
}

/**
 * The ANN query builder (plan §6): pick a vector column → paste/upload a query vector or reference
 * an existing row → choose LIMIT → get `SELECT … ORDER BY <col> ANN OF [...] LIMIT k`.
 *
 * Hybrid queries are first-class here: SAI predicates and the ANN clause go into one statement.
 * The generated CQL is always shown and always editable before execution — nothing runs silently.
 */
export function AnnQueryBuilderForm({
  keyspace,
  table,
  columns,
  onPreview,
  onRun,
  preview,
}: AnnQueryBuilderFormProps) {
  const [state, setState] = useState<AnnBuilderState>(() => ({
    ...emptyBuilderState(keyspace, table),
    column: columns[0] ?? null,
  }));
  const [vectorText, setVectorText] = useState('');
  const [parseError, setParseError] = useState<string | null>(null);
  const [rowKeyText, setRowKeyText] = useState('');
  const fileInput = useRef<HTMLInputElement | null>(null);

  const problem = useMemo(() => annBuilderProblem(state), [state]);

  const patch = (changes: Partial<AnnBuilderState>) =>
    setState((current) => ({ ...current, keyspace, table, ...changes }));

  function applyVectorText(text: string) {
    setVectorText(text);
    if (!text.trim()) {
      setParseError(null);
      patch({ values: null });
      return;
    }
    try {
      const values = parseVectorText(text, state.column?.dimensions);
      setParseError(null);
      patch({ values, fromRow: null });
    } catch (error) {
      setParseError(error instanceof VectorParseError ? error.message : String(error));
      patch({ values: null });
    }
  }

  function applyRowKey(text: string) {
    setRowKeyText(text);
    if (!text.trim()) {
      patch({ fromRow: null });
      return;
    }
    try {
      const parsed: unknown = JSON.parse(text);
      if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
        setParseError(null);
        patch({ fromRow: parsed as Record<string, unknown>, values: null });
      } else {
        setParseError('The row reference must be a JSON object of primary-key columns.');
      }
    } catch {
      setParseError('The row reference must be a JSON object of primary-key columns.');
    }
  }

  async function onFile(file: File | undefined) {
    if (!file) return;
    applyVectorText(await file.text());
  }

  function request(): AnnQueryRequest | null {
    try {
      return buildAnnRequest(state);
    } catch (error) {
      setParseError(error instanceof Error ? error.message : String(error));
      return null;
    }
  }

  function toggleProjection(fn: SimilarityFunction) {
    patch({
      similarityProjections: state.similarityProjections.includes(fn)
        ? state.similarityProjections.filter((existing) => existing !== fn)
        : [...state.similarityProjections, fn],
    });
  }

  function addPredicate() {
    patch({ predicates: [...state.predicates, { column: '', operator: '=', value: '' }] });
  }

  function updatePredicate(index: number, changes: Partial<AnnPredicate>) {
    patch({
      predicates: state.predicates.map((predicate, i) =>
        i === index ? { ...predicate, ...changes } : predicate,
      ),
    });
  }

  return (
    <Stack spacing={2} data-testid="ann-query-builder">
      <Stack direction="row" spacing={2} flexWrap="wrap" useFlexGap>
        <TextField
          select
          size="small"
          label="Vector column"
          value={state.column?.name ?? ''}
          onChange={(event) =>
            patch({ column: columns.find((c) => c.name === event.target.value) ?? null })
          }
          sx={{ minWidth: 220 }}
          slotProps={{ htmlInput: { 'aria-label': 'Vector column' } }}
        >
          {columns.map((column) => (
            <MenuItem key={column.name} value={column.name}>
              {column.name} — vector&lt;float, {column.dimensions}&gt;
              {column.index ? '' : ' (no SAI index)'}
            </MenuItem>
          ))}
        </TextField>

        <TextField
          size="small"
          type="number"
          label="LIMIT k"
          value={state.limit}
          onChange={(event) => patch({ limit: Number.parseInt(event.target.value, 10) })}
          sx={{ width: 120 }}
          slotProps={{ htmlInput: { min: 1, max: 10000, 'aria-label': 'LIMIT k' } }}
        />
      </Stack>

      <TextField
        multiline
        minRows={2}
        maxRows={6}
        size="small"
        label="Query vector"
        placeholder="[0.0123, -0.9871, 0.4412]  —  or comma/whitespace separated"
        value={vectorText}
        onChange={(event) => applyVectorText(event.target.value)}
        error={parseError !== null}
        helperText={
          parseError ??
          (state.values
            ? `${state.values.length} values parsed`
            : 'Paste values, upload a JSON array, or reference a row below.')
        }
        slotProps={{ htmlInput: { 'aria-label': 'Query vector' } }}
      />

      <Stack direction="row" spacing={1} alignItems="center">
        <Button size="small" onClick={() => fileInput.current?.click()}>
          Upload vector file
        </Button>
        <input
          ref={fileInput}
          type="file"
          accept="application/json,text/plain,.json,.txt"
          hidden
          data-testid="ann-vector-file"
          onChange={(event) => void onFile(event.target.files?.[0])}
        />
        <Typography variant="caption" color="text.secondary">
          JSON array of floats
        </Typography>
      </Stack>

      <TextField
        size="small"
        label="…or find rows similar to this one"
        placeholder='{"doc_id": "7c1a2b3d-…", "chunk_no": 0}'
        value={rowKeyText}
        onChange={(event) => applyRowKey(event.target.value)}
        helperText="Complete primary key of the reference row."
        slotProps={{ htmlInput: { 'aria-label': 'Reference row primary key' } }}
      />

      <Box>
        <Typography variant="caption" color="text.secondary" display="block" sx={{ mb: 0.5 }}>
          Score columns (sortable in the grid)
        </Typography>
        <Stack direction="row" spacing={1}>
          {SIMILARITY_FUNCTIONS.map((fn) => (
            <Chip
              key={fn}
              size="small"
              label={`similarity_${fn}`}
              color={state.similarityProjections.includes(fn) ? 'secondary' : 'default'}
              variant={state.similarityProjections.includes(fn) ? 'filled' : 'outlined'}
              onClick={() => toggleProjection(fn)}
            />
          ))}
        </Stack>
      </Box>

      <Box>
        <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 1 }}>
          <Typography variant="caption" color="text.secondary">
            SAI predicates (hybrid query)
          </Typography>
          <Button size="small" onClick={addPredicate} data-testid="ann-add-predicate">
            Add predicate
          </Button>
        </Stack>
        <Stack spacing={1}>
          {state.predicates.map((predicate, index) => (
            // Predicates are positional and freely reorderable in value only; index is the key.
            <Stack key={index} direction="row" spacing={1}>
              <TextField
                size="small"
                label="Column"
                value={predicate.column}
                onChange={(event) => updatePredicate(index, { column: event.target.value })}
              />
              <TextField
                select
                size="small"
                label="Operator"
                value={predicate.operator}
                onChange={(event) =>
                  updatePredicate(index, { operator: event.target.value as PredicateOperator })
                }
                sx={{ minWidth: 130 }}
              >
                {PREDICATE_OPERATORS.map((operator) => (
                  <MenuItem key={operator} value={operator}>
                    {operator}
                  </MenuItem>
                ))}
              </TextField>
              <TextField
                size="small"
                label="Value"
                value={String(predicate.value ?? '')}
                onChange={(event) => updatePredicate(index, { value: event.target.value })}
              />
            </Stack>
          ))}
        </Stack>
      </Box>

      <FormControlLabel
        control={
          <Switch
            size="small"
            checked={state.includeVectorColumn}
            onChange={(event) => patch({ includeVectorColumn: event.target.checked })}
          />
        }
        label="Include the raw vector in the results (large)"
      />

      {problem && (
        <Alert severity="info" variant="outlined" data-testid="ann-builder-problem">
          {problem}
        </Alert>
      )}

      {preview?.warnings?.map((warning) => (
        <Alert key={warning} severity="warning" variant="outlined">
          {warning}
        </Alert>
      ))}

      {preview && (
        <Box>
          <Typography variant="caption" color="text.secondary" display="block">
            Preview CQL — editable before execution
          </Typography>
          <Box
            component="pre"
            data-testid="ann-preview-cql"
            sx={{
              m: 0,
              p: 1.5,
              borderRadius: 1,
              bgcolor: 'action.hover',
              fontSize: 12,
              overflow: 'auto',
              whiteSpace: 'pre-wrap',
              wordBreak: 'break-word',
            }}
          >
            {preview.abbreviatedCql ?? preview.cql}
          </Box>
        </Box>
      )}

      <Stack direction="row" spacing={1}>
        <Button
          variant="outlined"
          size="small"
          disabled={problem !== null}
          onClick={() => {
            const built = request();
            if (built) onPreview?.(built);
          }}
        >
          Preview CQL
        </Button>
        <Button
          variant="contained"
          size="small"
          disabled={problem !== null}
          onClick={() => {
            const built = request();
            if (built) onRun?.(built);
          }}
        >
          Run ANN query
        </Button>
      </Stack>
    </Stack>
  );
}
