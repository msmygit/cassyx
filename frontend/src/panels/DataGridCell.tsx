import { useMemo } from 'react';
import Box from '@mui/material/Box';
import Chip from '@mui/material/Chip';
import Tooltip from '@mui/material/Tooltip';
import Typography from '@mui/material/Typography';
import { cqlKind, renderAnnotation, renderValue, type RenderOptions } from '../query/cqlValue';
import type { ColumnMetadata } from '../query/types';

export interface DataGridCellProps {
  value: unknown;
  column: ColumnMetadata;
  /** `false` when the row simply does not carry the key — which means *unset*, not null. */
  present: boolean;
  options?: RenderOptions;
}

/**
 * Type-aware cell renderer (plan §7).
 *
 * The thing worth noticing here is the top of the function: **null and unset render differently**.
 * Cassandra treats them differently — `null` writes a tombstone, unset writes nothing — and every
 * other Cassandra GUI shows both as an empty cell, which makes it impossible to tell whether a
 * column was written with a null or never written at all.
 */
export function DataGridCell({ value, column, present, options }: DataGridCellProps) {
  const kind = cqlKind(column);
  const annotation = useMemo(() => renderAnnotation(value, column), [value, column]);

  if (!present || value === '$unset') {
    return (
      <Tooltip title="Unset — this column was never written. Not the same as null, which writes a tombstone.">
        <Typography
          component="span"
          variant="caption"
          sx={{ color: 'text.disabled', fontStyle: 'italic' }}
        >
          ⊘ unset
        </Typography>
      </Tooltip>
    );
  }

  if (value === null || value === undefined) {
    return (
      <Tooltip title="Null — a tombstone was written here. Not the same as unset.">
        <Typography
          component="span"
          variant="caption"
          sx={{ color: 'warning.main', fontStyle: 'italic' }}
        >
          null
        </Typography>
      </Tooltip>
    );
  }

  if (kind === 'vector' && Array.isArray(value)) {
    return <VectorCell values={value as number[]} />;
  }

  if (column.name === '[applied]' || typeof value === 'boolean') {
    return (
      <Chip
        size="small"
        variant="outlined"
        color={value ? 'success' : 'error'}
        label={String(value)}
        sx={{ height: 18, fontSize: '0.7rem' }}
      />
    );
  }

  const text = renderValue(value, column, options);
  const body = (
    <Typography
      component="span"
      variant="caption"
      sx={{ fontFamily: 'monospace', whiteSpace: 'nowrap' }}
    >
      {text}
    </Typography>
  );

  if (!annotation) return body;
  return (
    <Tooltip title={annotation} placement="top-start">
      <Box component="span" sx={{ borderBottom: '1px dotted', borderColor: 'text.disabled' }}>
        {body}
      </Box>
    </Tooltip>
  );
}

/** Vectors render as a sparkline plus a dimension badge, never 1536 comma-separated floats. */
function VectorCell({ values }: { values: number[] }) {
  const points = useMemo(() => {
    if (values.length === 0) return '';
    const sample =
      values.length > 64
        ? values.filter((_, i) => i % Math.ceil(values.length / 64) === 0)
        : values;
    const min = Math.min(...sample);
    const max = Math.max(...sample);
    const range = max - min || 1;
    return sample
      .map(
        (v, i) => `${(i / Math.max(sample.length - 1, 1)) * 60},${12 - ((v - min) / range) * 12}`,
      )
      .join(' ');
  }, [values]);

  return (
    <Tooltip title={`${values.length} dimensions`}>
      <Box sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.5 }}>
        <Box component="svg" width={60} height={12} sx={{ display: 'block' }}>
          <polyline
            points={points}
            fill="none"
            stroke="currentColor"
            strokeWidth={1}
            opacity={0.7}
          />
        </Box>
        <Typography variant="caption" color="text.secondary">
          {values.length}d
        </Typography>
      </Box>
    </Tooltip>
  );
}
