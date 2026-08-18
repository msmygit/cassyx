import { useState } from 'react';
import Alert from '@mui/material/Alert';
import Button from '@mui/material/Button';
import Chip from '@mui/material/Chip';
import CircularProgress from '@mui/material/CircularProgress';
import Dialog from '@mui/material/Dialog';
import DialogActions from '@mui/material/DialogActions';
import DialogContent from '@mui/material/DialogContent';
import DialogTitle from '@mui/material/DialogTitle';
import List from '@mui/material/List';
import ListItem from '@mui/material/ListItem';
import ListItemButton from '@mui/material/ListItemButton';
import ListItemText from '@mui/material/ListItemText';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import AddRoundedIcon from '@mui/icons-material/AddRounded';
import {
  useConnect,
  useConnections,
  useDeleteConnection,
  useDisconnect,
} from '../connections/useConnections';

export interface ConnectionsDialogProps {
  open: boolean;
  onClose: () => void;
  activeConnectionId: string | null;
  onSelect: (connectionId: string) => void;
  onNewConnection: () => void;
  /** `false` suspends the list query — the shell's non-live mode. */
  live?: boolean;
}

/**
 * The connections surface (plan §3): every saved connection, its live session state, and the
 * connect / disconnect / delete actions.
 *
 * Multi-cluster is a first-class requirement, so this is a manager rather than a picker — the
 * top-bar dropdown switches between clusters, this decides which ones exist and which have
 * sessions.
 */
export function ConnectionsDialog({
  open,
  onClose,
  activeConnectionId,
  onSelect,
  onNewConnection,
  live = true,
}: ConnectionsDialogProps) {
  const [pendingId, setPendingId] = useState<string | null>(null);
  const connections = useConnections(live && open);
  const connect = useConnect();
  const disconnect = useDisconnect();
  const remove = useDeleteConnection();

  const items = connections.data ?? [];
  const error = connect.error ?? disconnect.error ?? remove.error ?? connections.error;

  const run = (id: string, mutate: (id: string) => void) => {
    setPendingId(id);
    mutate(id);
  };

  const busy = (id: string) =>
    pendingId === id && (connect.isPending || disconnect.isPending || remove.isPending);

  return (
    <Dialog open={open} onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>Connections</DialogTitle>
      <DialogContent dividers>
        {error && (
          <Alert severity="error" sx={{ mb: 2 }} data-testid="connections-error">
            {error.message}
          </Alert>
        )}

        {items.length === 0 ? (
          <Stack spacing={1} sx={{ py: 3 }} alignItems="center" data-testid="connections-empty">
            <Typography variant="body2" color="text.secondary">
              No connections yet.
            </Typography>
            <Typography variant="caption" color="text.secondary">
              Add a Cassandra/DSE cluster, an Astra DB database, or a raw HOCON configuration.
            </Typography>
          </Stack>
        ) : (
          <List dense disablePadding data-testid="connections-list">
            {items.map((connection) => (
              <ListItem
                key={connection.id}
                data-testid={`connection-row-${connection.id}`}
                disableGutters
                secondaryAction={
                  <Stack direction="row" spacing={0.5} alignItems="center">
                    {busy(connection.id) && <CircularProgress size={14} />}
                    {connection.connected ? (
                      <Button
                        size="small"
                        onClick={() => run(connection.id, disconnect.mutate)}
                        data-testid={`disconnect-${connection.id}`}
                      >
                        Disconnect
                      </Button>
                    ) : (
                      <Button
                        size="small"
                        variant="outlined"
                        onClick={() => run(connection.id, connect.mutate)}
                        data-testid={`connect-${connection.id}`}
                      >
                        Connect
                      </Button>
                    )}
                    <Button
                      size="small"
                      color="error"
                      onClick={() => run(connection.id, remove.mutate)}
                      data-testid={`delete-${connection.id}`}
                    >
                      Delete
                    </Button>
                  </Stack>
                }
              >
                <ListItemButton
                  selected={connection.id === activeConnectionId}
                  onClick={() => onSelect(connection.id)}
                  sx={{ pr: 24 }}
                >
                  <ListItemText
                    primary={
                      <Stack direction="row" spacing={1} alignItems="center">
                        <Typography variant="body2">{connection.name}</Typography>
                        <Chip size="small" variant="outlined" label={connection.mode} />
                        {connection.connected && (
                          <Chip size="small" color="success" label="session" />
                        )}
                      </Stack>
                    }
                    secondary={
                      connection.contactPoints
                        ?.map((point) => `${point.host}:${point.port}`)
                        .join(', ') ??
                      connection.description ??
                      null
                    }
                  />
                </ListItemButton>
              </ListItem>
            ))}
          </List>
        )}
      </DialogContent>
      <DialogActions>
        <Button
          startIcon={<AddRoundedIcon />}
          onClick={onNewConnection}
          data-testid="add-connection"
        >
          New connection
        </Button>
        <Button onClick={onClose}>Close</Button>
      </DialogActions>
    </Dialog>
  );
}
