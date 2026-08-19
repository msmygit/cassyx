import { useMemo, useState } from 'react';
import Alert from '@mui/material/Alert';
import AlertTitle from '@mui/material/AlertTitle';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Dialog from '@mui/material/Dialog';
import DialogActions from '@mui/material/DialogActions';
import DialogContent from '@mui/material/DialogContent';
import DialogContentText from '@mui/material/DialogContentText';
import DialogTitle from '@mui/material/DialogTitle';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import RefreshRoundedIcon from '@mui/icons-material/RefreshRounded';
import { Link } from 'react-router';
import {
  CountStatisticsView,
  useCreateCountJob,
  useDsbulkDefaults,
  type CountJobRequest,
} from '../bulk/dsbulk';
import { AppError } from '../api/errors';
import { useTableInfo, useTableStatistics } from '../schema/schemaQueries';
import { useWorkspace } from '../layout/workspaceContext';
import { EmptyState } from './EmptyState';

/**
 * Statistics tab (plan §4 / §5.4).
 *
 * The prior art had a statistics API and no UI at all. Here the cached COUNT snapshot renders, and
 * when there is none the page says so and offers to start the job rather than showing an error.
 *
 * The target is the selected node's OWN `{keyspace, table}` — same rule as everywhere else.
 *
 * Two things this page refuses to do:
 *   • **Ask for a mode the table cannot produce.** `partitions` needs a clustering column — DSBulk
 *     throws at workflow init otherwise — so it is offered only when the table has one. Asking
 *     anyway is answered `422` by the server, and that reason is shown rather than a failed job.
 *   • **Start a full scan on one click.** A count reads every row on every replica; the confirm
 *     dialog states that consequence, with the probe's own numbers, before it happens.
 */
export function StatisticsPage() {
  const workspace = useWorkspace();
  const [error, setError] = useState<string | null>(null);
  const [started, setStarted] = useState(false);
  const [confirming, setConfirming] = useState(false);

  const connectionId = workspace.activeConnectionId;
  const selected = workspace.selectedTable;

  const statistics = useTableStatistics(
    workspace.live ? connectionId : null,
    selected?.keyspace,
    selected?.table,
  );
  // Not gated on `live`: the field list is what decides which statistics modes are offered, and the
  // info panel reads it the same way. Offering `partitions` on a table whose shape we never looked
  // at is exactly the failure this is here to prevent.
  const tableInfo = useTableInfo(connectionId, selected?.keyspace, selected?.table);
  const countJob = useCreateCountJob(connectionId ?? '');

  /**
   * `partitions` is DSBulk's largest-partitions report, computed with a `GROUP BY` over the
   * partition key. With no clustering column every partition holds exactly one row, so DSBulk
   * refuses the mode outright — it is not a degraded result, it is a workflow that will not start.
   */
  const hasClusteringKey = useMemo(
    () => (tableInfo.data?.fields ?? []).some((field) => field.kind === 'CLUSTERING'),
    [tableInfo.data],
  );
  const modes = useMemo<NonNullable<CountJobRequest['modes']>>(
    () =>
      hasClusteringKey
        ? (['global', 'ranges', 'partitions'] as const).slice()
        : (['global', 'ranges'] as const).slice(),
    [hasClusteringKey],
  );

  // The cluster facts behind the cost warning. Only fetched once the user opens the dialog: it is a
  // live probe, and this page must not probe on every selection change.
  const preflight = useDsbulkDefaults(
    connectionId ?? '',
    { operation: 'COUNT', keyspace: selected?.keyspace, table: selected?.table },
    { enabled: Boolean(workspace.live && connectionId && selected && confirming) },
  );

  if (!connectionId) {
    return (
      <EmptyState
        testId="statistics-empty"
        title="No connection selected"
        detail="Pick or create a connection in the top bar to read table statistics."
      />
    );
  }

  if (!selected) {
    return (
      <EmptyState
        testId="statistics-empty"
        title="No table selected"
        detail="Choose a table in the schema tree — statistics are per table."
      />
    );
  }

  const recalculate = () => {
    setError(null);
    setConfirming(false);
    countJob.mutate(
      { keyspace: selected.keyspace, table: selected.table, modes, topPartitions: 10 },
      {
        onSuccess: () => setStarted(true),
        onError: (cause) => setError(describe(cause)),
      },
    );
  };

  return (
    <Box sx={{ flex: 1, minHeight: 0, overflow: 'auto' }} data-testid="statistics-page">
      <Stack
        direction="row"
        spacing={1.5}
        alignItems="center"
        sx={{ px: 1.5, py: 1, borderBottom: 1, borderColor: 'chrome.border' }}
      >
        <Typography variant="subtitle2">Statistics</Typography>
        <Typography variant="caption" color="text.secondary" sx={{ fontFamily: 'monospace' }}>
          {selected.keyspace}.{selected.table}
        </Typography>
        <Box sx={{ flex: 1 }} />
        <Button
          size="small"
          startIcon={<RefreshRoundedIcon />}
          disabled={countJob.isPending}
          onClick={() => {
            setError(null);
            setConfirming(true);
          }}
          data-testid="recalculate-statistics"
        >
          Recalculate
        </Button>
      </Stack>

      {error && (
        <Alert severity="error" sx={{ m: 1.5 }} data-testid="statistics-error">
          {error}
        </Alert>
      )}

      {started && (
        <Alert severity="info" sx={{ m: 1.5 }} data-testid="statistics-job-started">
          Count job queued — <Link to="/jobs">follow its progress in the jobs panel</Link>.
        </Alert>
      )}

      <Dialog
        open={confirming}
        onClose={() => setConfirming(false)}
        data-testid="statistics-confirm"
      >
        <DialogTitle>
          Count {selected.keyspace}.{selected.table}?
        </DialogTitle>
        <DialogContent>
          <DialogContentText component="div">
            <Alert severity="warning" sx={{ mb: 1.5 }}>
              <AlertTitle>This is a full table scan</AlertTitle>
              Cassandra stores no row count, so every row of {selected.keyspace}.{selected.table} is
              read off disk to answer this. It competes with production reads for the same page
              cache, and on a large table it can run for a long time.
            </Alert>
            <Typography variant="body2" gutterBottom data-testid="statistics-cost">
              {costLine(preflight.data)}
            </Typography>
            <Typography variant="body2" color="text.secondary">
              Modes: <code>{modes.join(', ')}</code>
              {!hasClusteringKey && (
                <>
                  {' — '}
                  <span data-testid="statistics-partitions-unavailable">
                    the largest-partitions report needs a clustering column, and this table has
                    none, so that mode is not requested.
                  </span>
                </>
              )}
            </Typography>
          </DialogContentText>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setConfirming(false)}>Cancel</Button>
          <Button
            onClick={recalculate}
            variant="contained"
            disabled={countJob.isPending}
            data-testid="statistics-confirm-run"
          >
            Run the count
          </Button>
        </DialogActions>
      </Dialog>

      <Box sx={{ p: 1.5 }}>
        <CountStatisticsView
          statistics={statistics.data ?? undefined}
          loading={statistics.isPending}
        />
      </Box>
    </Box>
  );
}

/** The probe's own numbers when we have them, and an honest shrug when we do not. */
function costLine(
  defaults: { probe?: { nodeCount?: number; estimatedRows?: number | null } } | undefined,
): string {
  const probe = defaults?.probe;
  if (!probe) return 'Estimating the cost from the cluster…';
  const nodes = probe.nodeCount ?? 0;
  const rows = probe.estimatedRows;
  const scale =
    rows === null || rows === undefined
      ? 'The size of this table is not known ahead of the count.'
      : `Roughly ${rows.toLocaleString()} rows, by the cluster's own estimate.`;
  return `${scale} The scan fans out across ${nodes} ${nodes === 1 ? 'node' : 'nodes'}.`;
}

/**
 * The server's reason, not a generic failure.
 *
 * A refused statistics mode comes back as `422` with a problem body naming the modes; showing
 * `Request failed` there would recreate exactly the opaque failure this page exists to avoid.
 */
function describe(cause: Error): string {
  if (cause instanceof AppError && cause.problem?.detail) {
    return cause.problem.detail;
  }
  return cause.message;
}
