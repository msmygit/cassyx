import Alert from '@mui/material/Alert';
import Chip from '@mui/material/Chip';
import MenuItem from '@mui/material/MenuItem';
import Stack from '@mui/material/Stack';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import { useQuery } from '@tanstack/react-query';
import { listJobTemplates, type JobTemplate } from '../bulk/dsbulk/dsbulkApi';

export interface JobTemplatePickerProps {
  value: string | null;
  onChange: (template: JobTemplate | null) => void;
  /** Narrows the list, e.g. to `LOAD` on the load page. */
  operation?: JobTemplate['operation'];
  live?: boolean;
  label?: string;
}

const JOB_TEMPLATES_KEY = ['job-templates'] as const;

/**
 * Job-template picker (plan §5.3).
 *
 * `listJobTemplates` / `createJobTemplate` were implemented by the DSBulk workstream with no UI
 * owning them; this is that owner. Selecting a template stamps its id onto the submitted job so
 * the server resolves the saved settings rather than the client re-sending them.
 */
export function JobTemplatePicker({
  value,
  onChange,
  operation,
  live = true,
  label = 'Job template',
}: JobTemplatePickerProps) {
  const templates = useQuery({
    queryKey: JOB_TEMPLATES_KEY,
    queryFn: () => listJobTemplates(),
    enabled: live,
  });

  const items = (templates.data ?? []).filter(
    (template) => !operation || template.operation === operation,
  );

  if (templates.isError) {
    return (
      <Alert severity="warning" data-testid="job-templates-error">
        Job templates are unavailable: {templates.error.message}
      </Alert>
    );
  }

  return (
    <TextField
      select
      size="small"
      label={label}
      value={items.some((template) => template.id === value) ? value : ''}
      onChange={(event) =>
        onChange(items.find((template) => template.id === event.target.value) ?? null)
      }
      sx={{ minWidth: 260 }}
      slotProps={{ htmlInput: { 'data-testid': 'job-template-picker' } }}
      helperText={
        items.length === 0 && !templates.isPending ? 'No saved templates yet.' : undefined
      }
    >
      <MenuItem value="">
        <em>No template</em>
      </MenuItem>
      {items.map((template) => (
        <MenuItem key={template.id} value={template.id}>
          <Stack direction="row" spacing={1} alignItems="center">
            <Typography variant="body2">{template.name}</Typography>
            <Chip size="small" variant="outlined" label={template.operation} />
          </Stack>
        </MenuItem>
      ))}
    </TextField>
  );
}
