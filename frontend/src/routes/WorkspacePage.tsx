import { useState } from 'react';
import Box from '@mui/material/Box';
import IconButton from '@mui/material/IconButton';
import Tooltip from '@mui/material/Tooltip';
import Typography from '@mui/material/Typography';
import InfoOutlinedIcon from '@mui/icons-material/InfoOutlined';
import CloseRoundedIcon from '@mui/icons-material/CloseRounded';
import { QueryWorkspace } from '../panels/QueryWorkspace';
import { TableInfoPanel } from '../schema/TableInfoPanel';
import { activeTab, useTabs } from '../layout/tabsModel';
import { TabBar } from '../layout/TabBar';
import { useWorkspace } from '../layout/workspaceContext';

/**
 * Main work area: the tab strip over the query workspace (plan §5.1, §7, §8), with the table
 * info panel (§4) as an optional right-hand aside.
 *
 * The aside is fed from `workspace.selectedTable`, which is resolved from the selected node's OWN
 * `{keyspace, table}` identity. That is the whole point: selecting `demo.users` shows `demo.users`
 * even when a `system_auth.users` node sits next to it in the tree.
 */
export function WorkspacePage() {
  const { state, dispatch } = useTabs();
  const workspace = useWorkspace();
  const current = activeTab(state);
  const [infoOpen, setInfoOpen] = useState(true);

  const connectionId = workspace.activeConnectionId;
  const selected = workspace.selectedTable;
  const showInfo = infoOpen && selected !== null && connectionId !== null;

  // A tab opened from the tree knows its own keyspace; otherwise fall back to the connection's.
  const defaultKeyspace =
    current?.identity?.keyspace ?? workspace.activeConnection?.defaultKeyspace ?? undefined;

  return (
    <Box sx={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column' }}>
      <Box sx={{ display: 'flex', alignItems: 'stretch' }}>
        <Box sx={{ flex: 1, minWidth: 0 }}>
          <TabBar
            tabs={state.tabs}
            activeTabId={state.activeTabId}
            onActivate={(id) => dispatch({ type: 'activate', id })}
            onClose={(id) => dispatch({ type: 'close', id })}
            onNew={() =>
              dispatch({
                type: 'open',
                tab: { kind: 'query', title: `Query ${state.sequence + 1}`, content: '' },
              })
            }
          />
        </Box>
        {selected && connectionId && (
          <Tooltip
            title={
              infoOpen ? 'Hide table info' : `Show info for ${selected.keyspace}.${selected.table}`
            }
          >
            <IconButton
              size="small"
              aria-label="Toggle table info"
              data-testid="toggle-table-info"
              onClick={() => setInfoOpen((open) => !open)}
              sx={{ alignSelf: 'center', mx: 0.5 }}
            >
              {infoOpen ? (
                <CloseRoundedIcon fontSize="small" />
              ) : (
                <InfoOutlinedIcon fontSize="small" />
              )}
            </IconButton>
          </Tooltip>
        )}
      </Box>

      <Box sx={{ flex: 1, minHeight: 0, display: 'flex' }}>
        <Box sx={{ flex: 1, minWidth: 0, minHeight: 0, display: 'flex', flexDirection: 'column' }}>
          {current ? (
            <QueryWorkspace
              connectionId={connectionId}
              value={current.content}
              onChange={(content) => dispatch({ type: 'update', id: current.id, content })}
              completionSchema={workspace.completionSchema}
              {...(defaultKeyspace ? { defaultKeyspace } : {})}
            />
          ) : (
            <Box sx={{ flex: 1, display: 'grid', placeItems: 'center' }} data-testid="no-tabs">
              <Typography variant="body2" color="text.secondary">
                No open tabs — press + to start a query.
              </Typography>
            </Box>
          )}
        </Box>

        {showInfo && (
          <Box
            component="aside"
            data-testid="table-info-aside"
            sx={{
              width: 380,
              flex: '0 0 380px',
              minHeight: 0,
              overflow: 'auto',
              borderLeft: 1,
              borderColor: 'chrome.border',
              bgcolor: 'chrome.sidebar',
            }}
          >
            <TableInfoPanel
              // `key` forces a clean panel per table: stale tabs from the previous table would
              // otherwise show the wrong object's data for a frame.
              key={`${selected.keyspace}.${selected.table}`}
              connectionId={connectionId}
              identity={{ keyspace: selected.keyspace, table: selected.table }}
            />
          </Box>
        )}
      </Box>
    </Box>
  );
}
