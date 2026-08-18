import { useMemo, useState } from 'react';
import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Chip from '@mui/material/Chip';
import Collapse from '@mui/material/Collapse';
import Divider from '@mui/material/Divider';
import LinearProgress from '@mui/material/LinearProgress';
import Link from '@mui/material/Link';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import type { EventSourceFactory } from '../api/sse';
import { defaultJobsApi, type Job, type JobFilters, type JobsApi } from '../bulk/jobsApi';
import {
  canCancel,
  canDownload,
  formatBytes,
  formatCount,
  formatEta,
  formatRowsPerSecond,
  isTerminal,
  jobLabel,
  jobStatusColor,
  formatTimestamp,
  primaryArtifact,
  progressPercent,
} from '../bulk/jobsModel';
import { useJobEvents } from '../bulk/useJobEvents';
import { useCancelJob, useJobLogsQuery, useJobsQuery } from '../bulk/useJobs';
import { PanelPlaceholder } from './PanelPlaceholder';

export interface JobsPanelProps {
  /** Explicit job list. When omitted the panel fetches `GET /api/jobs` itself. */
  jobs?: Job[];
  filters?: JobFilters;
  /** Transport seam — tests and other workstreams can swap the implementation. */
  api?: JobsApi;
  /** Injectable `EventSource` constructor for the progress stream. */
  eventSourceFactory?: EventSourceFactory;
  /** `false` disables polling and the SSE subscription (static rendering, screenshots, E2E). */
  live?: boolean;
}

interface LogRow {
  at: string;
  level: string;
  message: string;
}

const LOG_TAIL = 500;

/**
 * Jobs panel (plan §5.5).
 *
 * Every long-running operation — unload, load, count, dump, keyspace copy, import — is a Job with
 * a persisted row, an SSE progress stream, a cancel action, retained logs and a downloadable
 * artifact. Bulk data never round-trips through the browser: the Download control is a plain
 * `<a href download>` pointing at the streaming artifact endpoint, never a `fetch` of the bytes.
 *
 * All of the logic lives in `src/bulk/` (`jobsModel`, `useJobEvents`, `useJobs`) — this file is
 * rendering only, which is also why `src/panels/**` is excluded from the coverage gate.
 */
export function JobsPanel({
  jobs,
  filters = {},
  api = defaultJobsApi,
  eventSourceFactory,
  live = true,
}: JobsPanelProps) {
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [logsOpen, setLogsOpen] = useState(false);

  const listQuery = useJobsQuery(filters, { api, enabled: jobs === undefined && live });
  const fetched = listQuery.data?.items;
  const items = useMemo(() => jobs ?? fetched ?? [], [jobs, fetched]);

  const selected = useMemo(
    () => items.find((job) => job.id === selectedId) ?? items[0] ?? null,
    [items, selectedId],
  );

  // Live progress only matters for a job that has not settled; a finished one has its final
  // numbers in the list payload already.
  const streamEnabled = live && selected !== null && !isTerminal(selected.status);
  const {
    job: detail,
    logs: streamedLogs,
    error: streamError,
  } = useJobEvents(selected, {
    enabled: streamEnabled,
    factory: eventSourceFactory,
    url: selected?.eventsUrl,
  });

  const cancelMutation = useCancelJob({ api });

  const retainedLogs = useJobLogsQuery(
    logsOpen && streamedLogs.length === 0 ? (detail?.id ?? null) : null,
    { tail: LOG_TAIL },
    { api },
  );

  const logRows: LogRow[] =
    streamedLogs.length > 0
      ? streamedLogs.map((line) => ({ at: line.at, level: line.level, message: line.message }))
      : (retainedLogs.data?.lines ?? []).map((line) => ({
          at: line.at,
          level: line.level,
          message: line.message,
        }));

  if (items.length === 0) {
    return (
      <PanelPlaceholder
        title="Jobs"
        section="§5.5"
        workstream="D / E"
        testId="jobs-panel-empty"
        todo={[
          'Launch unload / load / count jobs from the schema tree and query editor',
          'Reusable job templates and the DSBulk “view generated command” pane',
        ]}
      >
        <Typography variant="body2" color="text.secondary">
          No jobs yet. Unload, load, count, dump, copy and import all appear here with live
          progress.
        </Typography>
      </PanelPlaceholder>
    );
  }

  const percent = detail ? progressPercent(detail) : null;
  const progress = detail?.progress;
  const artifact = detail ? primaryArtifact(detail) : null;

  return (
    <Stack
      data-testid="jobs-panel"
      spacing={1}
      sx={{ height: '100%', minHeight: 0, p: 2, overflow: 'auto' }}
    >
      <Stack spacing={1}>
        {items.map((job) => {
          const row = detail && detail.id === job.id ? detail : job;
          const isSelected = selected?.id === job.id;
          return (
            <Box
              key={job.id}
              component="button"
              type="button"
              data-testid={`job-row-${job.id}`}
              aria-pressed={isSelected}
              onClick={() => setSelectedId(job.id)}
              sx={{
                width: '100%',
                textAlign: 'left',
                font: 'inherit',
                color: 'inherit',
                cursor: 'pointer',
                p: 1.5,
                border: 1,
                borderColor: isSelected ? 'primary.main' : 'chrome.border',
                borderRadius: 1,
                background: 'none',
              }}
            >
              <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap">
                <Typography variant="body2" sx={{ fontWeight: 600 }}>
                  {jobLabel(row)}
                </Typography>
                <Chip size="small" variant="outlined" label={row.type} />
                <Chip size="small" color={jobStatusColor(row.status)} label={row.status} />
                {row.engine && <Chip size="small" variant="outlined" label={row.engine} />}
                <Box sx={{ flexGrow: 1 }} />
                <Typography variant="caption" color="text.secondary">
                  {formatTimestamp(row.createdAt)}
                </Typography>
              </Stack>
            </Box>
          );
        })}
      </Stack>

      {detail && (
        <Box
          data-testid="job-detail"
          sx={{ p: 1.5, border: 1, borderColor: 'chrome.border', borderRadius: 1 }}
        >
          <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 1 }}>
            <Typography variant="subtitle2">{jobLabel(detail)}</Typography>
            <Chip size="small" color={jobStatusColor(detail.status)} label={detail.status} />
            <Box sx={{ flexGrow: 1 }} />
            {canCancel(detail) && (
              <Button
                size="small"
                color="warning"
                variant="outlined"
                data-testid="job-cancel"
                disabled={cancelMutation.isPending}
                onClick={() => cancelMutation.mutate(detail.id)}
              >
                Cancel
              </Button>
            )}
            {canDownload(detail) && artifact && (
              <Link
                component="a"
                data-testid="job-download"
                variant="body2"
                href={api.artifactUrl(detail.id, artifact.artifactId)}
                download={artifact.fileName}
              >
                Download {artifact.fileName} ({formatBytes(artifact.sizeBytes)})
              </Link>
            )}
          </Stack>

          <LinearProgress
            data-testid="job-progress"
            aria-label={`Progress for ${jobLabel(detail)}`}
            color={jobStatusColor(detail.status) === 'default' ? 'primary' : undefined}
            // Determinate whenever a percentage is knowable — including the native engine's
            // splitsCompleted/splitsTotal fallback (see `progressPercent`).
            variant={percent === null ? 'indeterminate' : 'determinate'}
            value={percent ?? 0}
            sx={{ mb: 1 }}
          />

          <Stack
            direction="row"
            spacing={2}
            flexWrap="wrap"
            data-testid="job-readout"
            sx={{ color: 'text.secondary' }}
          >
            <Typography variant="caption">
              {percent === null ? 'progress —' : `${percent.toFixed(1)}%`}
            </Typography>
            <Typography variant="caption">
              rows {formatCount(progress?.rowsProcessed)}
              {progress?.totalRowsEstimate ? ` / ~${formatCount(progress.totalRowsEstimate)}` : ''}
            </Typography>
            <Typography variant="caption">
              {formatRowsPerSecond(progress?.rowsPerSecond)}
            </Typography>
            <Typography variant="caption">ETA {formatEta(progress?.etaMillis)}</Typography>
            {progress?.bytesWritten !== undefined && (
              <Typography variant="caption">
                {formatBytes(progress.bytesWritten)} written
              </Typography>
            )}
            {progress?.currentPhase && (
              <Typography variant="caption">{progress.currentPhase}</Typography>
            )}
          </Stack>

          {detail.error && (
            <Alert severity="error" sx={{ mt: 1 }} data-testid="job-error">
              {detail.error.title ?? 'Job failed'}
              {detail.error.detail ? ` — ${detail.error.detail}` : ''}
            </Alert>
          )}
          {streamError && (
            <Alert severity="warning" sx={{ mt: 1 }} data-testid="job-stream-error">
              Live progress unavailable; falling back to polling.
            </Alert>
          )}

          <Divider sx={{ my: 1 }} />

          <Button
            size="small"
            data-testid="job-logs-toggle"
            aria-expanded={logsOpen}
            onClick={() => setLogsOpen((open) => !open)}
          >
            {logsOpen ? 'Hide logs' : 'Show logs'}
          </Button>
          <Collapse in={logsOpen} unmountOnExit>
            <Box
              data-testid="job-logs"
              sx={{
                mt: 1,
                p: 1,
                maxHeight: 220,
                overflow: 'auto',
                bgcolor: 'action.hover',
                borderRadius: 1,
                fontFamily: 'monospace',
                fontSize: 12,
              }}
            >
              {logRows.length === 0 ? (
                <Typography variant="caption" color="text.secondary">
                  No log lines retained for this job yet.
                </Typography>
              ) : (
                logRows.map((line, index) => (
                  <Box key={`${line.at}-${index}`} component="div">
                    {`${line.level} ${line.message}`}
                  </Box>
                ))
              )}
            </Box>
          </Collapse>
        </Box>
      )}
    </Stack>
  );
}
