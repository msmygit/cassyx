import Box from '@mui/material/Box';
import IconButton from '@mui/material/IconButton';
import Stack from '@mui/material/Stack';
import Tooltip from '@mui/material/Tooltip';
import Typography from '@mui/material/Typography';
import AccountTreeRoundedIcon from '@mui/icons-material/AccountTreeRounded';
import PlaylistPlayRoundedIcon from '@mui/icons-material/PlaylistPlayRounded';
import ScatterPlotRoundedIcon from '@mui/icons-material/ScatterPlotRounded';
import QueryStatsRoundedIcon from '@mui/icons-material/QueryStatsRounded';
import UploadFileRoundedIcon from '@mui/icons-material/UploadFileRounded';
import { NavLink, Outlet, useNavigate } from 'react-router';
import { useLicense } from '../license/licenseModel';
import { BypassBanner } from '../license/BypassBanner';
import { useDisconnect } from '../connections/useConnections';
import { capabilityState } from '../vector/vectorModel';
import { statementForNode, qualifiedName, type SchemaNode } from '../schema/model';
import type { CapabilityName } from '../schema/schemaTypes';
import { layout } from '../theme/tokens';
import { ConnectionBar } from './ConnectionBar';
import { ResizableSidebar } from './ResizableSidebar';
import { SchemaSidebar } from './SchemaSidebar';
import { useTabs } from './tabsModel';
import { useWorkspace } from './workspaceContext';

interface NavItem {
  to: string;
  label: string;
  icon: typeof AccountTreeRoundedIcon;
  end: boolean;
  /** Hidden behind an explanation when the connected cluster cannot do this (plan §7.1). */
  capability?: CapabilityName;
}

const NAV_ITEMS: NavItem[] = [
  { to: '/', label: 'Workspace', icon: AccountTreeRoundedIcon, end: true },
  { to: '/jobs', label: 'Jobs', icon: PlaylistPlayRoundedIcon, end: true },
  { to: '/jobs/load', label: 'Load data', icon: UploadFileRoundedIcon, end: false },
  { to: '/statistics', label: 'Statistics', icon: QueryStatsRoundedIcon, end: false },
  {
    to: '/vector',
    label: 'Vector & ANN',
    icon: ScatterPlotRoundedIcon,
    end: false,
    capability: 'vector',
  },
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
  const disconnect = useDisconnect();

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
        activeConnectionId={workspace.activeConnectionId}
        connections={workspace.connections}
        status={workspace.status}
        live={workspace.live}
        clusterName={workspace.capabilities?.clusterName ?? null}
        releaseVersion={workspace.capabilities?.releaseVersion ?? null}
        onSelect={(id) => workspace.setActiveConnectionId(id || null)}
        onDisconnect={() => {
          if (workspace.activeConnectionId) disconnect.mutate(workspace.activeConnectionId);
        }}
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
          {NAV_ITEMS.map((item) => {
            // "Unknown" is not "unsupported": before the probe has run, do not hide half the
            // product. Once it has, an unsupported feature is disabled with the probe's own reason.
            const gate =
              item.capability && workspace.capabilities
                ? capabilityState(workspace.capabilities, item.capability)
                : null;

            if (gate && !gate.supported) {
              return (
                <Tooltip key={item.to} title={gate.reason} placement="right">
                  <span data-testid={`nav-disabled-${item.label}`}>
                    <IconButton
                      size="small"
                      aria-label={item.label}
                      disabled
                      sx={{ borderRadius: 1.5 }}
                    >
                      <item.icon fontSize="small" />
                    </IconButton>
                  </span>
                </Tooltip>
              );
            }

            return (
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
            );
          })}
        </Stack>

        <ResizableSidebar>
          <SchemaSidebar onOpenInEditor={openNode} />
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
        {workspace.selectedTable && (
          <Typography
            variant="caption"
            color="text.secondary"
            sx={{ fontFamily: 'monospace' }}
            data-testid="status-selection"
          >
            {workspace.selectedTable.keyspace}.{workspace.selectedTable.table}
          </Typography>
        )}
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
