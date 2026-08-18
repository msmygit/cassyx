import { useMemo, useState } from 'react';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Divider from '@mui/material/Divider';
import Stack from '@mui/material/Stack';
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableRow from '@mui/material/TableRow';
import Typography from '@mui/material/Typography';
import { VectorSparkline } from './VectorSparkline';
import { SIMILARITY_FUNCTIONS, type SimilarityFunction } from './types';
import {
  abbreviateVector,
  formatComponent,
  magnitude,
  similarity,
  toJsonArray,
} from './vectorModel';

export interface VectorInspectorProps {
  values: readonly number[];
  columnName?: string;
  dimensions?: number;
  /** The vector of the currently selected row, for the similarity comparison. */
  comparisonValues?: readonly number[] | null;
  comparisonLabel?: string;
  /** Values shown before the "show all" control. 1536 rows of float is not a useful default. */
  initialComponentCount?: number;
}

/**
 * The expandable vector inspector (plan §6): full values, magnitude, and similarity to the
 * selected row.
 *
 * Components are paged rather than dumped: rendering 1536 table rows is slow and unreadable, and
 * the reason to open this panel is almost always "what does the start of this vector look like"
 * or "how close is it to that other row".
 */
export function VectorInspector({
  values,
  columnName = 'vector',
  dimensions,
  comparisonValues,
  comparisonLabel = 'selected row',
  initialComponentCount = 32,
}: VectorInspectorProps) {
  const [shown, setShown] = useState(initialComponentCount);
  const count = dimensions ?? values.length;

  const scores = useMemo(() => {
    if (!comparisonValues || comparisonValues.length !== values.length) return null;
    return SIMILARITY_FUNCTIONS.map((fn: SimilarityFunction) => ({
      fn,
      score: similarity(values, comparisonValues, fn),
    }));
  }, [values, comparisonValues]);

  const mismatch =
    comparisonValues != null && comparisonValues.length !== values.length
      ? `Cannot compare: ${comparisonLabel} has ${comparisonValues.length} dimensions, this one has ${values.length}.`
      : null;

  return (
    <Stack spacing={1.5} sx={{ p: 2, minWidth: 320 }} data-testid="vector-inspector">
      <Stack direction="row" spacing={1} alignItems="center" justifyContent="space-between">
        <Typography variant="subtitle2">{columnName}</Typography>
        <VectorSparkline values={values} dimensions={count} />
      </Stack>

      <Stack direction="row" spacing={3}>
        <Metric label="dimensions" value={String(count)} />
        <Metric
          label="magnitude"
          value={formatComponent(magnitude(values))}
          testId="vector-magnitude"
        />
      </Stack>

      {scores && (
        <Box data-testid="vector-similarity">
          <Typography variant="caption" color="text.secondary">
            similarity to {comparisonLabel}
          </Typography>
          <Stack direction="row" spacing={3} sx={{ mt: 0.5 }}>
            {scores.map(({ fn, score }) => (
              <Metric key={fn} label={fn} value={formatComponent(score)} />
            ))}
          </Stack>
        </Box>
      )}

      {mismatch && (
        <Typography variant="caption" color="warning.main" data-testid="vector-similarity-mismatch">
          {mismatch}
        </Typography>
      )}

      <Divider />

      <Box sx={{ maxHeight: 260, overflow: 'auto' }}>
        <Table size="small" aria-label={`${columnName} components`}>
          <TableBody>
            {values.slice(0, shown).map((value, index) => (
              // Index IS the identity of a vector component; there is nothing else to key on.
              <TableRow key={index} hover>
                <TableCell sx={{ width: 64, color: 'text.secondary', py: 0.25 }}>{index}</TableCell>
                <TableCell sx={{ fontFamily: 'monospace', py: 0.25 }}>
                  {formatComponent(value)}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </Box>

      {values.length > shown && (
        <Button size="small" onClick={() => setShown(values.length)} data-testid="vector-show-all">
          Show all {values.length} values
        </Button>
      )}

      <Typography variant="caption" color="text.secondary" sx={{ wordBreak: 'break-all' }}>
        {values.length > 8 ? abbreviateVector(values) : toJsonArray(values)}
      </Typography>
    </Stack>
  );
}

function Metric({ label, value, testId }: { label: string; value: string; testId?: string }) {
  return (
    <Box data-testid={testId}>
      <Typography variant="caption" color="text.secondary" display="block">
        {label}
      </Typography>
      <Typography variant="body2" sx={{ fontFamily: 'monospace' }}>
        {value}
      </Typography>
    </Box>
  );
}
