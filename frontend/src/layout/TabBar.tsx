import Box from '@mui/material/Box';
import IconButton from '@mui/material/IconButton';
import Tooltip from '@mui/material/Tooltip';
import Typography from '@mui/material/Typography';
import AddRoundedIcon from '@mui/icons-material/AddRounded';
import CloseRoundedIcon from '@mui/icons-material/CloseRounded';
import CircleIcon from '@mui/icons-material/Circle';
import { layout } from '../theme/tokens';
import type { EditorTab } from './tabsModel';

export interface TabBarProps {
  tabs: EditorTab[];
  activeTabId: string | null;
  onActivate: (id: string) => void;
  onClose: (id: string) => void;
  onNew: () => void;
}

/**
 * Multi-tab strip (plan §8).
 *
 * Multi-tab editing was a documented gap in the prior art, which could only ever show one query
 * at a time. Tabs opened from the schema tree carry the source object's fully-qualified identity,
 * so two tabs for `demo.users` and `system_auth.users` coexist and stay distinguishable.
 */
export function TabBar({ tabs, activeTabId, onActivate, onClose, onNew }: TabBarProps) {
  return (
    <Box
      role="tablist"
      data-testid="tab-bar"
      sx={{
        height: layout.tabBarHeight,
        flex: `0 0 ${layout.tabBarHeight}px`,
        display: 'flex',
        alignItems: 'stretch',
        bgcolor: 'chrome.bar',
        borderBottom: 1,
        borderColor: 'chrome.border',
        overflowX: 'auto',
      }}
    >
      {tabs.map((tab) => {
        const active = tab.id === activeTabId;
        return (
          <Box
            key={tab.id}
            role="tab"
            aria-selected={active}
            tabIndex={active ? 0 : -1}
            data-testid={`tab-${tab.id}`}
            title={
              tab.identity
                ? `${tab.identity.keyspace}${tab.identity.table ? `.${tab.identity.table}` : ''}`
                : tab.title
            }
            onClick={() => onActivate(tab.id)}
            onAuxClick={(event) => {
              if (event.button === 1) onClose(tab.id);
            }}
            sx={{
              display: 'flex',
              alignItems: 'center',
              gap: 0.75,
              px: 1.5,
              minWidth: 120,
              maxWidth: 240,
              cursor: 'pointer',
              borderRight: 1,
              borderColor: 'chrome.border',
              borderBottom: active ? 2 : 0,
              borderBottomColor: 'primary.main',
              bgcolor: active ? 'background.paper' : 'transparent',
              '&:hover': { bgcolor: active ? 'background.paper' : 'chrome.hover' },
            }}
          >
            <Typography
              variant="body2"
              sx={{
                whiteSpace: 'nowrap',
                overflow: 'hidden',
                textOverflow: 'ellipsis',
                fontWeight: active ? 600 : 400,
              }}
            >
              {tab.title}
            </Typography>
            {tab.dirty && (
              <Tooltip title="Unsaved changes">
                <CircleIcon sx={{ fontSize: 8, color: 'warning.main' }} />
              </Tooltip>
            )}
            <IconButton
              size="small"
              aria-label={`Close ${tab.title}`}
              onClick={(event) => {
                event.stopPropagation();
                onClose(tab.id);
              }}
              sx={{ ml: 'auto', p: 0.25 }}
            >
              <CloseRoundedIcon sx={{ fontSize: 14 }} />
            </IconButton>
          </Box>
        );
      })}

      <Tooltip title="New query tab">
        <IconButton size="small" aria-label="New query tab" onClick={onNew} sx={{ mx: 0.5 }}>
          <AddRoundedIcon fontSize="small" />
        </IconButton>
      </Tooltip>
    </Box>
  );
}
