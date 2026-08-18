import { useState } from 'react';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import UploadFileRoundedIcon from '@mui/icons-material/UploadFileRounded';
import { Link } from 'react-router';
import { JobsPanel } from '../panels/JobsPanel';
import { useWorkspace } from '../layout/workspaceContext';
import { JobTemplatePicker } from './JobTemplatePicker';

/**
 * Jobs route (plan §5.5): the live job list plus the launcher affordances that belong next to it —
 * the template picker and the entry point into the load flow.
 */
export function JobsPage() {
  const workspace = useWorkspace();
  const [templateId, setTemplateId] = useState<string | null>(null);

  return (
    <Box sx={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column' }}>
      <Stack
        direction="row"
        spacing={1.5}
        alignItems="center"
        sx={{ px: 1.5, py: 1, borderBottom: 1, borderColor: 'chrome.border' }}
      >
        <Typography variant="subtitle2">Jobs</Typography>
        <Box sx={{ flex: 1 }} />
        <JobTemplatePicker
          value={templateId}
          onChange={(template) => setTemplateId(template?.id ?? null)}
          live={workspace.live}
        />
        <Button
          component={Link}
          to={templateId ? `/jobs/load?templateId=${encodeURIComponent(templateId)}` : '/jobs/load'}
          size="small"
          variant="contained"
          startIcon={<UploadFileRoundedIcon />}
          data-testid="new-load-job"
        >
          Load data
        </Button>
      </Stack>

      <Box sx={{ flex: 1, minHeight: 0 }}>
        <JobsPanel live={workspace.live} />
      </Box>
    </Box>
  );
}
