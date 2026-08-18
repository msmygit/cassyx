import { useState } from 'react';
import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { useNavigate, useSearchParams } from 'react-router';
import { LoadJobForm, useCreateLoadJob, type LoadJobRequest } from '../bulk/dsbulk';
import { useWorkspace } from '../layout/workspaceContext';
import { EmptyState } from './EmptyState';
import { JobTemplatePicker } from './JobTemplatePicker';

/**
 * Load flow (plan §5.3). Launched from the jobs page or from the schema tree's
 * "Load data into…" entry, which pre-fills `keyspace`/`table` from the clicked node's own identity.
 */
export function LoadJobPage() {
  const workspace = useWorkspace();
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const [templateId, setTemplateId] = useState<string | null>(params.get('templateId'));
  const [error, setError] = useState<string | null>(null);

  const connectionId = workspace.activeConnectionId;
  const create = useCreateLoadJob(connectionId ?? '');

  if (!connectionId) {
    return (
      <EmptyState
        testId="load-job-empty"
        title="No connection selected"
        detail="Pick or create a connection in the top bar — a load job needs a target cluster."
      />
    );
  }

  const keyspace = params.get('keyspace') ?? workspace.selectedTable?.keyspace ?? '';
  const table = params.get('table') ?? workspace.selectedTable?.table ?? '';

  const submit = async (request: LoadJobRequest) => {
    setError(null);
    try {
      await create.mutateAsync(templateId ? { ...request, templateId } : request);
      void navigate('/jobs');
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Could not start the load job.');
    }
  };

  return (
    <Box sx={{ flex: 1, minHeight: 0, overflow: 'auto' }} data-testid="load-job-page">
      <Stack
        direction="row"
        spacing={1.5}
        alignItems="center"
        sx={{ px: 1.5, py: 1, borderBottom: 1, borderColor: 'chrome.border' }}
      >
        <Typography variant="subtitle2">Load data</Typography>
        <Box sx={{ flex: 1 }} />
        <JobTemplatePicker
          value={templateId}
          onChange={(template) => setTemplateId(template?.id ?? null)}
          operation="LOAD"
          live={workspace.live}
        />
      </Stack>

      {error && (
        <Alert severity="error" sx={{ m: 1.5 }} data-testid="load-job-error">
          {error}
        </Alert>
      )}

      <Box sx={{ p: 1.5 }}>
        <LoadJobForm
          connectionId={connectionId}
          keyspace={keyspace}
          table={table}
          onSubmit={submit}
        />
      </Box>
    </Box>
  );
}
