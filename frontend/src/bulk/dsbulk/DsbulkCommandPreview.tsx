/**
 * "View generated command" (plan §5.3).
 *
 * Renders `BulkCommandPreview`: the copyable one-line invocation, the exact `argv` handed to the
 * DSBulk runner, and the generated per-job HOCON. This is what makes cassyx double as a DSBulk
 * command builder for people who will run the job elsewhere.
 *
 * `maskedFields` is shown EXPLICITLY: the preview has had secrets redacted, and a user copying the
 * command needs to know which values they must substitute rather than discovering it from a
 * confusing authentication failure at run time.
 */
import { useState } from 'react';
import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Chip from '@mui/material/Chip';
import Divider from '@mui/material/Divider';
import Paper from '@mui/material/Paper';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import ContentCopyRoundedIcon from '@mui/icons-material/ContentCopyRounded';
import DownloadRoundedIcon from '@mui/icons-material/DownloadRounded';
import type { BulkCommandPreview as BulkCommandPreviewPayload } from './dsbulkApi';

export interface DsbulkCommandPreviewProps {
  preview: BulkCommandPreviewPayload | undefined;
  loading?: boolean;
  /** Name offered for the downloaded HOCON file. */
  fileName?: string;
}

/** Data URL rather than an object URL: no lifecycle to leak, and it works in jsdom. */
function hoconDataUrl(hocon: string): string {
  return `data:text/plain;charset=utf-8,${encodeURIComponent(hocon)}`;
}

async function copyText(text: string): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch {
    return false;
  }
}

function MonoBlock({ children, testId }: { children: string; testId: string }) {
  return (
    <Paper
      variant="outlined"
      data-testid={testId}
      sx={{
        p: 1.5,
        fontFamily: 'monospace',
        fontSize: '0.8rem',
        whiteSpace: 'pre-wrap',
        wordBreak: 'break-all',
        overflowX: 'auto',
      }}
    >
      {children}
    </Paper>
  );
}

export function DsbulkCommandPreview({
  preview,
  loading = false,
  fileName = 'dsbulk.conf',
}: DsbulkCommandPreviewProps) {
  const [copied, setCopied] = useState<string | null>(null);

  if (loading && !preview) {
    return (
      <Typography variant="body2" color="text.secondary" data-testid="command-preview-loading">
        Generating command…
      </Typography>
    );
  }

  if (!preview) {
    return (
      <Typography variant="body2" color="text.secondary" data-testid="command-preview-empty">
        Choose a keyspace and table to preview the generated <code>dsbulk</code> command.
      </Typography>
    );
  }

  const masked = preview.maskedFields ?? [];

  const handleCopy = (label: string, text: string) => {
    void copyText(text).then((ok) => setCopied(ok ? label : null));
  };

  return (
    <Stack spacing={2} data-testid="command-preview">
      <Stack direction="row" spacing={1} alignItems="center" justifyContent="space-between">
        <Typography variant="subtitle2">Generated command</Typography>
        <Stack direction="row" spacing={1}>
          <Button
            size="small"
            startIcon={<ContentCopyRoundedIcon />}
            onClick={() => handleCopy('command', preview.command)}
          >
            Copy command
          </Button>
          <Button
            size="small"
            startIcon={<DownloadRoundedIcon />}
            component="a"
            href={hoconDataUrl(preview.hocon)}
            download={fileName}
          >
            Download .conf
          </Button>
        </Stack>
      </Stack>

      <MonoBlock testId="command-line">{preview.command}</MonoBlock>

      {copied && (
        <Alert severity="success" data-testid="copy-confirmation">
          Copied the {copied} to the clipboard.
        </Alert>
      )}

      {masked.length > 0 && (
        <Alert severity="info" data-testid="masked-fields">
          Secrets are redacted in this preview. Substitute your own values for:{' '}
          {masked.map((field) => (
            <Chip key={field} size="small" label={field} sx={{ mr: 0.5 }} />
          ))}
        </Alert>
      )}

      <Divider />

      <Box>
        <Typography variant="subtitle2" gutterBottom>
          Runner arguments
        </Typography>
        <MonoBlock testId="command-argv">{preview.argv.join('\n')}</MonoBlock>
      </Box>

      <Box>
        <Stack direction="row" spacing={1} alignItems="center" justifyContent="space-between">
          <Typography variant="subtitle2">Generated HOCON ({fileName})</Typography>
          <Button
            size="small"
            startIcon={<ContentCopyRoundedIcon />}
            onClick={() => handleCopy('configuration', preview.hocon)}
          >
            Copy HOCON
          </Button>
        </Stack>
        <MonoBlock testId="command-hocon">{preview.hocon}</MonoBlock>
      </Box>
    </Stack>
  );
}
