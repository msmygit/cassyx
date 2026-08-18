/**
 * Count / statistics output (plan §5.4) — the result of the DSBulk `count` workflow.
 *
 * Total rows, per-replica, per-token-range and the top-N largest partitions (the skew signal that
 * drives §5.2 oversplitting).
 *
 * CORRECTNESS: token boundaries are Murmur3 values spanning the full signed 64-bit range and row
 * counts are `int64`. Both arrive as strings/numbers that MUST NOT be passed through `Number()` for
 * display — `-9223372036854775808` does not survive the round trip. They are rendered verbatim.
 */
import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Chip from '@mui/material/Chip';
import Paper from '@mui/material/Paper';
import Stack from '@mui/material/Stack';
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableHead from '@mui/material/TableHead';
import TableRow from '@mui/material/TableRow';
import Typography from '@mui/material/Typography';
import type { Schemas } from '../../api/types';

export type TableStatistics = Schemas['TableStatistics'];

export interface CountStatisticsViewProps {
  statistics: TableStatistics | undefined;
  loading?: boolean;
}

/**
 * Group digits without going through `Number` — `totalRows` is an `int64` and large partition
 * counts genuinely exceed `Number.MAX_SAFE_INTEGER`.
 */
function groupDigits(value: number | string): string {
  const text = String(value);
  const negative = text.startsWith('-');
  const digits = negative ? text.slice(1) : text;
  if (!/^\d+$/.test(digits)) return text;
  const grouped = digits.replace(/\B(?=(\d{3})+(?!\d))/g, ' ');
  return negative ? `-${grouped}` : grouped;
}

function Metric({ label, value, testId }: { label: string; value: string; testId: string }) {
  return (
    <Paper variant="outlined" sx={{ p: 2, minWidth: 160 }}>
      <Typography variant="caption" color="text.secondary">
        {label}
      </Typography>
      <Typography variant="h6" data-testid={testId} sx={{ fontVariantNumeric: 'tabular-nums' }}>
        {value}
      </Typography>
    </Paper>
  );
}

export function CountStatisticsView({ statistics, loading = false }: CountStatisticsViewProps) {
  if (loading && !statistics) {
    return (
      <Typography variant="body2" color="text.secondary" data-testid="statistics-loading">
        Counting…
      </Typography>
    );
  }

  if (!statistics) {
    return (
      <Alert severity="info" data-testid="statistics-empty">
        No statistics yet. Run a count job to populate this panel.
      </Alert>
    );
  }

  const perReplica = statistics.perReplica ?? [];
  const perTokenRange = statistics.perTokenRange ?? [];
  const largestPartitions = statistics.largestPartitions ?? [];

  return (
    <Stack spacing={3} data-testid="count-statistics">
      <Stack direction="row" spacing={2} flexWrap="wrap" useFlexGap>
        <Metric label="Total rows" value={groupDigits(statistics.totalRows)} testId="total-rows" />
        {statistics.partitionCount !== undefined && statistics.partitionCount !== null && (
          <Metric
            label="Partitions"
            value={groupDigits(statistics.partitionCount)}
            testId="partition-count"
          />
        )}
        {statistics.durationMillis !== undefined && (
          <Metric
            label="Duration"
            value={`${groupDigits(statistics.durationMillis)} ms`}
            testId="duration"
          />
        )}
        <Metric label="Computed at" value={statistics.computedAt} testId="computed-at" />
      </Stack>

      <Box>
        <Typography variant="subtitle2" gutterBottom>
          Per replica
        </Typography>
        {perReplica.length === 0 ? (
          <Typography variant="body2" color="text.secondary">
            Not requested — enable the <code>hosts</code> statistics mode.
          </Typography>
        ) : (
          <Table size="small" data-testid="per-replica">
            <TableHead>
              <TableRow>
                <TableCell>Endpoint</TableCell>
                <TableCell>Datacenter</TableCell>
                <TableCell align="right">Rows</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {perReplica.map((replica) => (
                <TableRow key={replica.endpoint}>
                  <TableCell>{replica.endpoint}</TableCell>
                  <TableCell>{replica.datacenter ?? '—'}</TableCell>
                  <TableCell align="right">{groupDigits(replica.rows)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </Box>

      <Box>
        <Typography variant="subtitle2" gutterBottom>
          Per token range
        </Typography>
        {perTokenRange.length === 0 ? (
          <Typography variant="body2" color="text.secondary">
            Not requested — enable the <code>ranges</code> statistics mode.
          </Typography>
        ) : (
          <Table size="small" data-testid="per-token-range">
            <TableHead>
              <TableRow>
                <TableCell>Start token</TableCell>
                <TableCell>End token</TableCell>
                <TableCell align="right">Rows</TableCell>
                <TableCell>Replicas</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {perTokenRange.map((range) => (
                <TableRow key={`${range.start}:${range.end}`}>
                  {/* Rendered verbatim: these are int64 tokens, not JS numbers. */}
                  <TableCell sx={{ fontFamily: 'monospace' }}>{range.start}</TableCell>
                  <TableCell sx={{ fontFamily: 'monospace' }}>{range.end}</TableCell>
                  <TableCell align="right">{groupDigits(range.rows)}</TableCell>
                  <TableCell>
                    {(range.replicas ?? []).map((replica) => (
                      <Chip key={replica} size="small" label={replica} sx={{ mr: 0.5 }} />
                    ))}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </Box>

      <Box>
        <Typography variant="subtitle2" gutterBottom>
          Largest partitions
        </Typography>
        {largestPartitions.length === 0 ? (
          <Typography variant="body2" color="text.secondary">
            Not requested — enable the <code>biggest-partitions</code> statistics mode.
          </Typography>
        ) : (
          <Table size="small" data-testid="largest-partitions">
            <TableHead>
              <TableRow>
                <TableCell>Partition key</TableCell>
                <TableCell align="right">Rows</TableCell>
                <TableCell align="right">Size (bytes)</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {largestPartitions.map((partition) => (
                <TableRow key={partition.partitionKey}>
                  <TableCell sx={{ fontFamily: 'monospace' }}>{partition.partitionKey}</TableCell>
                  <TableCell align="right">{groupDigits(partition.rows)}</TableCell>
                  <TableCell align="right">
                    {partition.sizeBytes === undefined || partition.sizeBytes === null
                      ? '—'
                      : groupDigits(partition.sizeBytes)}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </Box>
    </Stack>
  );
}
