import Box from '@mui/material/Box';
import LinearProgress from '@mui/material/LinearProgress';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import type { Job } from '../api/types';
import { PanelPlaceholder } from './PanelPlaceholder';

export interface JobsPanelProps {
  jobs?: Job[];
}

/**
 * Jobs panel (plan §5.5). SHELL ONLY.
 *
 * Every long-running operation — unload, load, count, dump, keyspace copy, import — is a Job with
 * a persisted row, an SSE progress stream (`GET /api/jobs/{id}/events`, see `api/sse.ts`), a
 * cancel action, retained logs and a downloadable artifact. Bulk data never round-trips through
 * the browser; this panel only ever receives progress and a download handle.
 */
export function JobsPanel({ jobs = [] }: JobsPanelProps) {
  if (jobs.length === 0) {
    return (
      <PanelPlaceholder
        title="Jobs"
        section="§5.5"
        workstream="D / E"
        testId="jobs-panel-empty"
        todo={[
          'Subscribe to /api/jobs/{id}/events via subscribeToJobEvents (api/sse.ts)',
          'Cancel, retained logs, downloadable artifact, concurrent-job cap',
          'DSBulk “view generated command” pane and reusable job templates',
        ]}
      >
        <Typography variant="body2" color="text.secondary">
          No jobs yet. Unload, load, count, dump, copy and import all appear here with live
          progress.
        </Typography>
      </PanelPlaceholder>
    );
  }

  return (
    <Stack spacing={1} sx={{ p: 2, overflow: 'auto' }} data-testid="jobs-panel">
      {jobs.map((job) => (
        <Box key={job.id} sx={{ p: 1.5, border: 1, borderColor: 'chrome.border', borderRadius: 1 }}>
          <Stack direction="row" spacing={1} alignItems="center">
            <Typography variant="body2" sx={{ fontWeight: 600 }}>
              {job.name ?? job.type}
            </Typography>
            <Typography variant="caption" color="text.secondary">
              {job.status}
            </Typography>
          </Stack>
          <LinearProgress
            variant={
              job.progress?.percent === null || job.progress?.percent === undefined
                ? 'indeterminate'
                : 'determinate'
            }
            value={job.progress?.percent ?? 0}
            sx={{ mt: 1 }}
          />
        </Box>
      ))}
    </Stack>
  );
}
