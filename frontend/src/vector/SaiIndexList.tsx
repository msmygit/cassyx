import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Chip from '@mui/material/Chip';
import Stack from '@mui/material/Stack';
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableHead from '@mui/material/TableHead';
import TableRow from '@mui/material/TableRow';
import Typography from '@mui/material/Typography';
import type { SaiIndex, SaiIndexStatus } from './types';

export interface SaiIndexListProps {
  indexes: readonly SaiIndex[];
  /** Build state per index name, when it has been polled. */
  statuses?: Record<string, SaiIndexStatus | undefined>;
  onAlter?: (index: SaiIndex) => void;
  onDrop?: (index: SaiIndex) => void;
}

/**
 * SAI lifecycle list — vector and scalar indexes alike (plan §6).
 *
 * The build state matters and is shown: SAI builds per replica, so an index can be queryable on
 * one node and still building on another, which shows up as short ANN result sets rather than an
 * error. `buildProgressPercent` is the share of nodes reporting it built.
 */
export function SaiIndexList({ indexes, statuses, onAlter, onDrop }: SaiIndexListProps) {
  if (indexes.length === 0) {
    return (
      <Typography variant="body2" color="text.secondary" data-testid="sai-index-list-empty">
        No SAI indexes on this table. ANN queries need one on the vector column.
      </Typography>
    );
  }

  return (
    <Box data-testid="sai-index-list">
      <Table size="small">
        <TableHead>
          <TableRow>
            <TableCell>Index</TableCell>
            <TableCell>Target</TableCell>
            <TableCell>Similarity</TableCell>
            <TableCell>State</TableCell>
            <TableCell align="right">Actions</TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {indexes.map((index) => {
            const status = statuses?.[index.name];
            return (
              <TableRow key={index.name} hover>
                <TableCell sx={{ fontFamily: 'monospace' }}>{index.name}</TableCell>
                <TableCell>
                  <Stack direction="row" spacing={0.5} alignItems="center">
                    <span>{index.target}</span>
                    {index.vectorIndex && (
                      <Chip size="small" color="secondary" variant="outlined" label="vector" />
                    )}
                  </Stack>
                </TableCell>
                <TableCell>{index.similarityFunction ?? '—'}</TableCell>
                <TableCell>
                  {status ? (
                    <Chip
                      size="small"
                      label={
                        status.state === 'BUILDING' && status.buildProgressPercent != null
                          ? `BUILDING ${Math.round(status.buildProgressPercent)}%`
                          : status.state
                      }
                      color={
                        status.state === 'QUERYABLE'
                          ? 'success'
                          : status.state === 'FAILED'
                            ? 'error'
                            : 'default'
                      }
                      variant="outlined"
                    />
                  ) : (
                    '—'
                  )}
                </TableCell>
                <TableCell align="right">
                  <Stack direction="row" spacing={1} justifyContent="flex-end">
                    <Button size="small" onClick={() => onAlter?.(index)}>
                      Alter
                    </Button>
                    <Button size="small" color="error" onClick={() => onDrop?.(index)}>
                      Drop
                    </Button>
                  </Stack>
                </TableCell>
              </TableRow>
            );
          })}
        </TableBody>
      </Table>
      <Typography variant="caption" color="text.secondary" sx={{ mt: 1, display: 'block' }}>
        Cassandra has no <code>ALTER INDEX</code>: altering generates a drop-and-recreate pair,
        shown for review before anything runs.
      </Typography>
    </Box>
  );
}
