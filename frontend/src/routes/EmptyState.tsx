import Box from '@mui/material/Box';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import type { ReactNode } from 'react';

export interface EmptyStateProps {
  title: string;
  detail?: string;
  action?: ReactNode;
  testId?: string;
}

/**
 * The shell's shared "nothing to show yet" surface.
 *
 * Every route has to render sensibly with no connection selected — an empty state is a designed
 * screen here, not a crash or a blank pane.
 */
export function EmptyState({ title, detail, action, testId = 'empty-state' }: EmptyStateProps) {
  return (
    <Box sx={{ flex: 1, display: 'grid', placeItems: 'center', p: 4 }} data-testid={testId}>
      <Stack spacing={1} alignItems="center" sx={{ maxWidth: 420, textAlign: 'center' }}>
        <Typography variant="subtitle1">{title}</Typography>
        {detail && (
          <Typography variant="body2" color="text.secondary">
            {detail}
          </Typography>
        )}
        {action}
      </Stack>
    </Box>
  );
}
