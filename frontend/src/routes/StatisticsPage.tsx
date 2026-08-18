import { useState } from 'react';
import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import RefreshRoundedIcon from '@mui/icons-material/RefreshRounded';
import { Link } from 'react-router';
import { CountStatisticsView, useCreateCountJob } from '../bulk/dsbulk';
import { useTableStatistics } from '../schema/schemaQueries';
import { useWorkspace } from '../layout/workspaceContext';
import { EmptyState } from './EmptyState';

/**
 * Statistics tab (plan §4 / §5.4).
 *
 * The prior art had a statistics API and no UI at all. Here the cached COUNT snapshot renders, and
 * when there is none the page says so and offers to start the job rather than showing an error.
 *
 * The target is the selected node's OWN `{keyspace, table}` — same rule as everywhere else.
 */
export function StatisticsPage() {
  const workspace = useWorkspace();
  const [error, setError] = useState<string | null>(null);
  const [started, setStarted] = useState(false);

  const connectionId = workspace.activeConnectionId;
  const selected = workspace.selectedTable;

  const statistics = useTableStatistics(
    workspace.live ? connectionId : null,
    selected?.keyspace,
    selected?.table,
  );
  const countJob = useCreateCountJob(connectionId ?? '');

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
    countJob.mutate(
      {
        keyspace: selected.keyspace,
        table: selected.table,
        modes: ['global', 'ranges', 'partitions'],
        topPartitions: 10,
      },
      {
        onSuccess: () => setStarted(true),
        onError: (cause) => setError(cause.message),
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
          onClick={recalculate}
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

      <Box sx={{ p: 1.5 }}>
        <CountStatisticsView
          statistics={statistics.data ?? undefined}
          loading={statistics.isPending}
        />
      </Box>
    </Box>
  );
}
