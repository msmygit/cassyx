import type { ReactNode } from 'react';
import Box from '@mui/material/Box';
import Chip from '@mui/material/Chip';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';

export interface PanelPlaceholderProps {
  title: string;
  /** The plan section this panel implements. */
  section: string;
  /** The Phase 1 workstream that owns it (plan §10). */
  workstream: string;
  children?: ReactNode;
  /** Bullet list of what the finished panel must do. */
  todo?: string[];
  testId?: string;
}

/**
 * Shared "structure and states, not feature logic" shell for the Phase 0 panels.
 *
 * Each placeholder states which plan section and which Phase 1 workstream owns it, so the agent
 * that picks it up has the contract in front of it rather than in a separate document.
 */
export function PanelPlaceholder({
  title,
  section,
  workstream,
  children,
  todo,
  testId,
}: PanelPlaceholderProps) {
  return (
    <Box
      data-testid={testId}
      sx={{ height: '100%', minHeight: 0, display: 'flex', flexDirection: 'column', p: 2 }}
    >
      <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 1 }}>
        <Typography variant="h6">{title}</Typography>
        <Chip size="small" variant="outlined" label={section} />
        <Chip size="small" variant="outlined" color="primary" label={`workstream ${workstream}`} />
      </Stack>
      {children}
      {todo && todo.length > 0 && (
        <Box component="ul" sx={{ mt: 1, pl: 2.5, color: 'text.secondary' }}>
          {todo.map((item) => (
            <Typography key={item} component="li" variant="caption" sx={{ display: 'list-item' }}>
              {item}
            </Typography>
          ))}
        </Box>
      )}
    </Box>
  );
}
