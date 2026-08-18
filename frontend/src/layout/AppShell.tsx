import Box from '@mui/material/Box';
import IconButton from '@mui/material/IconButton';
import Stack from '@mui/material/Stack';
import Tooltip from '@mui/material/Tooltip';
import Typography from '@mui/material/Typography';
import AccountTreeRoundedIcon from '@mui/icons-material/AccountTreeRounded';
import PlaylistPlayRoundedIcon from '@mui/icons-material/PlaylistPlayRounded';
import ScatterPlotRoundedIcon from '@mui/icons-material/ScatterPlotRounded';
import { NavLink, Outlet, useNavigate } from 'react-router';
import { useLicense } from '../license/licenseModel';
import { BypassBanner } from '../license/BypassBanner';
import { SchemaTree } from '../schema/SchemaTree';
import { statementForNode, qualifiedName, type SchemaNode } from '../schema/model';
import { layout } from '../theme/tokens';
import { ConnectionBar } from './ConnectionBar';
import { ResizableSidebar } from './ResizableSidebar';
import { useTabs } from './tabsModel';
import { useWorkspace } from './workspaceContext';

const NAV_ITEMS = [
  { to: '/', label: 'Workspace', icon: AccountTreeRoundedIcon, end: true },
  { to: '/jobs', label: 'Jobs', icon: PlaylistPlayRoundedIcon, end: false },
  { to: '/vector', label: 'Vector & ANN', icon: ScatterPlotRoundedIcon, end: false },
];

/**
 * The application shell (plan §2):
 *
 *   ┌─ bypass banner (only in unlicensed-bypass mode, §9.2) ─┐
 *   ├─ connection bar ──────────────────────────────────────┤
 *   │ rail │ resizable schema sidebar │ work area (Outlet)  │
 *   └───────────────────────────────────────────────────────┘
 *
 * The banner is rendered ABOVE everything and is not dismissible.
 */
export function AppShell() {
  const license = useLicense();
  const workspace = useWorkspace();
  const { dispatch } = useTabs();
  const navigate = useNavigate();

  const openNode = (node: SchemaNode) => {
    if (node.kind !== 'TABLE' && node.kind !== 'VIEW') return;
    // Title and statement both come from the node's OWN identity.
    dispatch({
      type: 'openFromSchema',
      identity: node.identity,
      title: qualifiedName(node.identity),
      content: statementForNode(node, { limit: 500 }),
    });
    void navigate('/');
  };

  return (
    <Box sx={{ height: '100vh', display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
      {license.showBypassBanner && <BypassBanner />}

      <ConnectionBar
        connections={workspace.connections}
        activeConnectionId={workspace.activeConnectionId}
        status={workspace.status}
        onSelect={workspace.setActiveConnectionId}
      />

      <Box sx={{ flex: 1, minHeight: 0, display: 'flex' }}>
        <Stack
          component="nav"
          data-testid="nav-rail"
          alignItems="center"
          spacing={0.5}
          sx={{
            width: 48,
            flex: '0 0 48px',
            py: 1,
            bgcolor: 'chrome.bar',
            borderRight: 1,
            borderColor: 'chrome.border',
          }}
        >
          {NAV_ITEMS.map((item) => (
            <NavLink key={item.to} to={item.to} end={item.end} style={{ textDecoration: 'none' }}>
              {({ isActive }) => (
                <Tooltip title={item.label} placement="right">
                  <IconButton
                    size="small"
                    aria-label={item.label}
                    color={isActive ? 'primary' : 'default'}
                    sx={{ borderRadius: 1.5 }}
                  >
                    <item.icon fontSize="small" />
                  </IconButton>
                </Tooltip>
              )}
            </NavLink>
          ))}
        </Stack>

        <ResizableSidebar>
          <Box
            sx={{
              px: 1.5,
              py: 0.75,
              borderBottom: 1,
              borderColor: 'chrome.border',
              display: 'flex',
              alignItems: 'center',
            }}
          >
            <Typography variant="caption" sx={{ fontWeight: 700, letterSpacing: '0.06em' }}>
              SCHEMA
            </Typography>
          </Box>
          <SchemaTree
            nodes={workspace.schema}
            selectedId={workspace.selectedNodeId}
            onSelect={(node) => workspace.setSelectedNodeId(node.id)}
            onOpenInEditor={openNode}
          />
        </ResizableSidebar>

        <Box
          component="main"
          sx={{ flex: 1, minWidth: 0, minHeight: 0, display: 'flex', flexDirection: 'column' }}
        >
          <Outlet />
        </Box>
      </Box>

      <Box
        component="footer"
        sx={{
          height: layout.statusBarHeight,
          flex: `0 0 ${layout.statusBarHeight}px`,
          display: 'flex',
          alignItems: 'center',
          gap: 1.5,
          px: 1.5,
          bgcolor: 'chrome.bar',
          borderTop: 1,
          borderColor: 'chrome.border',
        }}
      >
        <Typography variant="caption" color="text.secondary">
          {workspace.status === 'CONNECTED' ? 'Session active' : 'No session'}
        </Typography>
        <Box sx={{ flex: 1 }} />
        <Typography variant="caption" color="text.secondary" data-testid="status-edition">
          {license.bypass
            ? 'edition: unlicensed-bypass'
            : `edition: ${license.status?.edition ?? '—'}`}
        </Typography>
      </Box>
    </Box>
  );
}
