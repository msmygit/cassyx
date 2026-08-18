import { useState } from 'react';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Chip from '@mui/material/Chip';
import Divider from '@mui/material/Divider';
import IconButton from '@mui/material/IconButton';
import MenuItem from '@mui/material/MenuItem';
import Select from '@mui/material/Select';
import Stack from '@mui/material/Stack';
import Tooltip from '@mui/material/Tooltip';
import Typography from '@mui/material/Typography';
import AddRoundedIcon from '@mui/icons-material/AddRounded';
import DarkModeRoundedIcon from '@mui/icons-material/DarkModeRounded';
import LightModeRoundedIcon from '@mui/icons-material/LightModeRounded';
import LinkRoundedIcon from '@mui/icons-material/LinkRounded';
import LinkOffRoundedIcon from '@mui/icons-material/LinkOffRounded';
import SettingsEthernetRoundedIcon from '@mui/icons-material/SettingsEthernetRounded';
import CircleIcon from '@mui/icons-material/Circle';
import type { ConnectionSummary, SessionStatus } from '../api/types';
import { ConnectionDialog } from '../connections/ConnectionDialog';
import type { ConnectionFormState } from '../connections/connectionModel';
import { useConnect, useConnectionHealth, useConnections } from '../connections/useConnections';
import { CassyxLogo } from '../theme/brand';
import { useColorMode } from '../theme/colorMode';
import { layout } from '../theme/tokens';
import { ConnectionsDialog } from './ConnectionsDialog';

export interface ConnectionBarProps {
  activeConnectionId: string | null;
  /** Override for the live `useConnections()` list — direct unit tests and static renders. */
  connections?: ConnectionSummary[];
  /** Override for the live health-derived indicator. */
  status?: SessionStatus;
  /** `false` suspends the live queries. */
  live?: boolean;
  onSelect?: (connectionId: string) => void;
  onDisconnect?: () => void;
  onCreate?: (form: ConnectionFormState, bundleFile: File | null) => void | Promise<void>;
  /** Cluster/release info shown next to the indicator once connected. */
  clusterName?: string | null;
  releaseVersion?: string | null;
}

const STATUS_COLOR: Record<SessionStatus, 'success' | 'warning' | 'error' | 'disabled'> = {
  CONNECTED: 'success',
  CONNECTING: 'warning',
  ERROR: 'error',
  DISCONNECTED: 'disabled',
};

const STATUS_LABEL: Record<SessionStatus, string> = {
  CONNECTED: 'Connected',
  CONNECTING: 'Connecting…',
  ERROR: 'Connection error',
  DISCONNECTED: 'Not connected',
};

/**
 * Top connection bar (plan §2 shell / §3).
 *
 * Holds the multi-cluster switcher, the live connection indicator, the connections manager, the
 * "new connection" entry point (all three modes), and the light/dark switch.
 *
 * The indicator is driven by `useConnectionHealth` — the health endpoint, polled — and the
 * per-entry session badges by `useConnections`. Nothing here is static: a session that dies
 * server-side goes grey here within one poll interval rather than lying until the next reload.
 */
export function ConnectionBar({
  activeConnectionId,
  connections: connectionsOverride,
  status: statusOverride,
  live = true,
  onSelect,
  onDisconnect,
  onCreate,
  clusterName,
  releaseVersion,
}: ConnectionBarProps) {
  const [dialogOpen, setDialogOpen] = useState(false);
  const [managerOpen, setManagerOpen] = useState(false);
  const { mode, toggle } = useColorMode();

  const connectionsQuery = useConnections(live && connectionsOverride === undefined);
  const connections = connectionsOverride ?? connectionsQuery.data ?? [];

  const health = useConnectionHealth(
    activeConnectionId ?? undefined,
    live && statusOverride === undefined,
  );
  const connect = useConnect();

  const status: SessionStatus =
    statusOverride ??
    (!activeConnectionId
      ? 'DISCONNECTED'
      : health.isError
        ? 'ERROR'
        : health.data
          ? health.data.status === 'DISCONNECTED'
            ? 'DISCONNECTED'
            : 'CONNECTED'
          : health.isPending
            ? 'CONNECTING'
            : 'DISCONNECTED');

  const indicatorDetail =
    health.data && health.data.status !== 'DISCONNECTED'
      ? `${health.data.status} · ${health.data.openConnections ?? 0} open, ${
          health.data.inFlightRequests ?? 0
        } in flight`
      : STATUS_LABEL[status];

  return (
    <Box
      component="header"
      data-testid="connection-bar"
      sx={{
        height: layout.connectionBarHeight,
        flex: `0 0 ${layout.connectionBarHeight}px`,
        display: 'flex',
        alignItems: 'center',
        gap: 1.5,
        px: 1.5,
        bgcolor: 'chrome.bar',
        borderBottom: 1,
        borderColor: 'chrome.border',
      }}
    >
      <CassyxLogo size="small" />

      <Divider orientation="vertical" flexItem sx={{ my: 1 }} />

      <Select
        size="small"
        displayEmpty
        value={
          connections.some((candidate) => candidate.id === activeConnectionId)
            ? (activeConnectionId ?? '')
            : ''
        }
        onChange={(event) => onSelect?.(event.target.value)}
        sx={{ minWidth: 220 }}
        inputProps={{ 'aria-label': 'Active connection', 'data-testid': 'connection-select' }}
      >
        <MenuItem value="">
          <em>No connection selected</em>
        </MenuItem>
        {connections.map((connection) => (
          <MenuItem key={connection.id} value={connection.id}>
            <Stack direction="row" spacing={1} alignItems="center">
              <Typography variant="body2">{connection.name}</Typography>
              <Chip size="small" variant="outlined" label={connection.mode} />
              {connection.connected && <Chip size="small" color="success" label="session" />}
            </Stack>
          </MenuItem>
        ))}
      </Select>

      <Tooltip title="Manage connections — connect, disconnect, delete">
        <IconButton
          size="small"
          aria-label="Manage connections"
          onClick={() => setManagerOpen(true)}
          data-testid="manage-connections"
        >
          <SettingsEthernetRoundedIcon fontSize="small" />
        </IconButton>
      </Tooltip>

      <Tooltip title="New connection — Cassandra/DSE, Astra DB or advanced HOCON">
        <IconButton
          size="small"
          aria-label="New connection"
          onClick={() => setDialogOpen(true)}
          data-testid="new-connection"
        >
          <AddRoundedIcon fontSize="small" />
        </IconButton>
      </Tooltip>

      <Tooltip title={indicatorDetail}>
        <Stack direction="row" spacing={0.75} alignItems="center" data-testid="connection-status">
          <CircleIcon sx={{ fontSize: 10 }} color={STATUS_COLOR[status]} />
          <Typography variant="caption" color="text.secondary">
            {STATUS_LABEL[status]}
          </Typography>
          {status === 'CONNECTED' && clusterName && (
            <Typography variant="caption" color="text.secondary" sx={{ fontFamily: 'monospace' }}>
              · {clusterName}
              {releaseVersion ? ` ${releaseVersion}` : ''}
            </Typography>
          )}
        </Stack>
      </Tooltip>

      <Box sx={{ flex: 1 }} />

      {status === 'CONNECTED' ? (
        <Button
          size="small"
          startIcon={<LinkOffRoundedIcon />}
          onClick={() => onDisconnect?.()}
          data-testid="disconnect"
        >
          Disconnect
        </Button>
      ) : (
        activeConnectionId && (
          <Button
            size="small"
            startIcon={<LinkRoundedIcon />}
            disabled={connect.isPending}
            onClick={() => connect.mutate(activeConnectionId)}
            data-testid="connect"
          >
            Connect
          </Button>
        )
      )}

      <Tooltip title={mode === 'dark' ? 'Switch to light mode' : 'Switch to dark mode'}>
        <IconButton
          size="small"
          onClick={toggle}
          aria-label="Toggle colour mode"
          data-testid="color-mode-toggle"
        >
          {mode === 'dark' ? (
            <LightModeRoundedIcon fontSize="small" />
          ) : (
            <DarkModeRoundedIcon fontSize="small" />
          )}
        </IconButton>
      </Tooltip>

      <ConnectionsDialog
        open={managerOpen}
        onClose={() => setManagerOpen(false)}
        activeConnectionId={activeConnectionId}
        live={live}
        onSelect={(id) => onSelect?.(id)}
        onNewConnection={() => {
          setManagerOpen(false);
          setDialogOpen(true);
        }}
      />

      <ConnectionDialog
        open={dialogOpen}
        onClose={() => setDialogOpen(false)}
        {...(onCreate ? { onSave: onCreate } : {})}
      />
    </Box>
  );
}
