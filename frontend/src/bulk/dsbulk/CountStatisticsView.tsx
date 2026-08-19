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

/**
 * The published `TableStatistics` plus the truncation markers the server sends.
 *
 * The range and replica sections are capped server-side — a 12-node vnode cluster reports ~3000
 * token ranges — and these four fields say that a cap applied and how many rows there really were.
 * They are additive to `openapi/cassyx-api.yaml` (which is owned by another workstream); the
 * contract addition is recorded in `docs/integration-todo.md`. Declared optional here so the view
 * behaves correctly against a server that does not send them.
 */
export type TableStatistics = Schemas['TableStatistics'] & {
  perReplicaTruncated?: boolean;
  perReplicaReported?: number;
  perTokenRangeTruncated?: boolean;
  perTokenRangeReported?: number;
};

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

/**
 * Says out loud that a section was capped.
 *
 * Without this the shortened list is indistinguishable from a small cluster, and a user reading the
 * per-range table would conclude their data lives in 500 ranges when it lives in 3000.
 */
function Truncation({
  truncated,
  shown,
  reported,
  unit,
  testId,
}: {
  truncated?: boolean;
  shown: number;
  reported?: number;
  unit: string;
  testId: string;
}) {
  if (!truncated) return null;
  return (
    <Alert severity="info" sx={{ mb: 1 }} data-testid={testId}>
      Showing the {shown} busiest of {reported ?? 'many'} {unit}. The rest are omitted — mostly
      empty ranges, and a full listing is thousands of rows on a vnode cluster.
    </Alert>
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
        {/*
          `partitionCount` is null for a DSBulk-sourced snapshot and the tile is simply absent.
          DSBulk reports the top-N largest partitions and no total, and the field previously carried
          the size of that top-N list — so every table in the world had exactly 10 partitions.
        */}
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
          <>
            <Truncation
              truncated={statistics.perReplicaTruncated}
              shown={perReplica.length}
              reported={statistics.perReplicaReported}
              unit="nodes"
              testId="per-replica-truncated"
            />
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
          </>
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
          <>
            <Truncation
              truncated={statistics.perTokenRangeTruncated}
              shown={perTokenRange.length}
              reported={statistics.perTokenRangeReported}
              unit="token ranges"
              testId="per-token-range-truncated"
            />
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
          </>
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
