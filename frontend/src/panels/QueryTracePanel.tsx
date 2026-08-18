import { useMemo } from 'react';
import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Chip from '@mui/material/Chip';
import LinearProgress from '@mui/material/LinearProgress';
import Stack from '@mui/material/Stack';
import Tooltip from '@mui/material/Tooltip';
import Typography from '@mui/material/Typography';
import type { QueryTrace } from '../query/types';

export interface QueryTracePanelProps {
  trace?: QueryTrace | null;
  loading?: boolean;
  error?: string | null;
}

/**
 * The `system_traces` timeline (plan §5.1).
 *
 * Tracing is one of the few places `cqlsh` still beats every GUI, so this renders the whole event
 * list — every activity, with its source node, thread and elapsed microseconds, and a bar showing
 * where the time actually went. A single "took 41ms" number would throw away the reason anyone
 * turns tracing on.
 */
export function QueryTracePanel({ trace, loading, error }: QueryTracePanelProps) {
  const maxElapsed = useMemo(
    () => Math.max(1, ...(trace?.events ?? []).map((event) => event.sourceElapsedMicros ?? 0)),
    [trace],
  );

  if (loading) return <LinearProgress data-testid="trace-loading" />;
  if (error) return <Alert severity="info">{error}</Alert>;
  if (!trace) {
    return (
      <Alert severity="info" variant="outlined" data-testid="trace-empty">
        Run a statement with <strong>Trace</strong> enabled to see its coordinator timeline here.
      </Alert>
    );
  }

  return (
    <Box sx={{ height: '100%', overflow: 'auto', p: 1 }} data-testid="query-trace">
      <Stack
        direction="row"
        spacing={1}
        alignItems="center"
        flexWrap="wrap"
        useFlexGap
        sx={{ mb: 1 }}
      >
        <Chip size="small" label={trace.requestType} />
        <Chip
          size="small"
          variant="outlined"
          label={`coordinator ${trace.coordinator ?? 'unknown'}`}
        />
        <Chip
          size="small"
          variant="outlined"
          color="primary"
          label={`${((trace.durationMicros ?? 0) / 1000).toFixed(2)} ms total`}
        />
        <Typography variant="caption" color="text.secondary">
          {trace.tracingId}
        </Typography>
      </Stack>

      {Object.entries(trace.parameters ?? {}).length > 0 && (
        <Box sx={{ mb: 1 }}>
          {Object.entries(trace.parameters ?? {}).map(([key, value]) => (
            <Typography
              key={key}
              variant="caption"
              component="div"
              sx={{ fontFamily: 'monospace' }}
            >
              {key} = {value}
            </Typography>
          ))}
        </Box>
      )}

      {(trace.events ?? []).map((event, index) => {
        const elapsed = event.sourceElapsedMicros ?? 0;
        return (
          <Box key={`${index}-${elapsed}`} data-testid="trace-event" sx={{ py: 0.25 }}>
            <Stack direction="row" spacing={1} alignItems="baseline">
              <Typography
                variant="caption"
                sx={{ width: 90, textAlign: 'right', fontFamily: 'monospace' }}
              >
                {(elapsed / 1000).toFixed(3)} ms
              </Typography>
              <Tooltip title={`${event.source} · ${event.threadName ?? ''}`}>
                <Typography variant="caption" sx={{ flex: 1 }}>
                  {event.activity}
                </Typography>
              </Tooltip>
            </Stack>
            <Box sx={{ ml: '98px', height: 3, bgcolor: 'chrome.border', borderRadius: 1 }}>
              <Box
                sx={{
                  width: `${(elapsed / maxElapsed) * 100}%`,
                  height: '100%',
                  bgcolor: 'primary.main',
                  borderRadius: 1,
                }}
              />
            </Box>
          </Box>
        );
      })}
    </Box>
  );
}
