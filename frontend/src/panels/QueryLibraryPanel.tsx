import { useCallback, useEffect, useState } from 'react';
import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Chip from '@mui/material/Chip';
import IconButton from '@mui/material/IconButton';
import List from '@mui/material/List';
import ListItemButton from '@mui/material/ListItemButton';
import ListItemText from '@mui/material/ListItemText';
import Stack from '@mui/material/Stack';
import TextField from '@mui/material/TextField';
import Tooltip from '@mui/material/Tooltip';
import Typography from '@mui/material/Typography';
import DeleteOutlineRoundedIcon from '@mui/icons-material/DeleteOutlineRounded';
import StarRoundedIcon from '@mui/icons-material/StarRounded';
import {
  clearQueryHistory,
  createSavedScript,
  deleteSavedScript,
  listQueryHistory,
  listSavedScripts,
} from '../query/api';
import type { QueryHistoryEntry, SavedScript } from '../query/types';

export interface QueryLibraryPanelProps {
  connectionId: string | null;
  currentCql: string;
  onLoadScript: (cql: string) => void;
}

/**
 * Query history with timings, plus saved / favourite scripts organised into folders (plan §8).
 *
 * History is server-persisted, not `localStorage`: the point of a history is that it survives the
 * browser, and a self-hosted deployment is frequently driven from more than one machine.
 */
export function QueryLibraryPanel({
  connectionId,
  currentCql,
  onLoadScript,
}: QueryLibraryPanelProps) {
  const [history, setHistory] = useState<QueryHistoryEntry[]>([]);
  const [scripts, setScripts] = useState<SavedScript[]>([]);
  const [search, setSearch] = useState('');
  const [name, setName] = useState('');
  const [folder, setFolder] = useState('');
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    try {
      const [historyPage, savedScripts] = await Promise.all([
        listQueryHistory({
          connectionId: connectionId ?? undefined,
          q: search || undefined,
          limit: 100,
        }),
        listSavedScripts(),
      ]);
      setHistory(historyPage.items);
      setScripts(savedScripts);
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, [connectionId, search]);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  const save = async () => {
    if (!name.trim() || !currentCql.trim()) return;
    try {
      await createSavedScript({
        name: name.trim(),
        cql: currentCql,
        folder: folder.trim() || undefined,
        favourite: false,
        connectionId: connectionId ?? undefined,
      });
      setName('');
      await refresh();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  return (
    <Box sx={{ height: '100%', overflow: 'auto', p: 1 }} data-testid="query-library">
      {error && (
        <Alert severity="error" sx={{ mb: 1 }}>
          {error}
        </Alert>
      )}

      <Stack direction="row" spacing={1} sx={{ mb: 1 }}>
        <TextField
          size="small"
          label="Save current script as"
          value={name}
          onChange={(event) => setName(event.target.value)}
          sx={{ flex: 1 }}
        />
        <TextField
          size="small"
          label="Folder"
          placeholder="/reports"
          value={folder}
          onChange={(event) => setFolder(event.target.value)}
          sx={{ width: 160 }}
        />
        <Button
          size="small"
          variant="outlined"
          onClick={() => void save()}
          data-testid="save-script"
        >
          Save
        </Button>
      </Stack>

      <Typography variant="overline" color="text.secondary">
        Saved scripts
      </Typography>
      <List dense data-testid="saved-scripts">
        {scripts.map((script) => (
          <ListItemButton key={script.id} onClick={() => onLoadScript(script.cql)}>
            {script.favourite && (
              <StarRoundedIcon fontSize="small" color="warning" sx={{ mr: 0.5 }} />
            )}
            <ListItemText
              primary={script.name}
              secondary={script.folder ?? '/'}
              primaryTypographyProps={{ variant: 'body2' }}
              secondaryTypographyProps={{ variant: 'caption' }}
            />
            <IconButton
              size="small"
              aria-label={`Delete ${script.name}`}
              onClick={(event) => {
                event.stopPropagation();
                void deleteSavedScript(script.id).then(refresh);
              }}
            >
              <DeleteOutlineRoundedIcon fontSize="small" />
            </IconButton>
          </ListItemButton>
        ))}
        {scripts.length === 0 && (
          <Typography variant="caption" color="text.secondary" sx={{ pl: 2 }}>
            No saved scripts yet.
          </Typography>
        )}
      </List>

      <Stack direction="row" spacing={1} alignItems="center" sx={{ mt: 2, mb: 1 }}>
        <Typography variant="overline" color="text.secondary" sx={{ flex: 1 }}>
          History
        </Typography>
        <TextField
          size="small"
          placeholder="Search statements"
          value={search}
          onChange={(event) => setSearch(event.target.value)}
        />
        <Button
          size="small"
          color="error"
          onClick={() => void clearQueryHistory(connectionId ?? undefined).then(refresh)}
        >
          Clear
        </Button>
      </Stack>

      <List dense data-testid="query-history">
        {history.map((entry) => (
          <ListItemButton key={entry.id} onClick={() => onLoadScript(entry.cql)}>
            <ListItemText
              primary={entry.cql}
              secondary={new Date(entry.executedAt).toLocaleString()}
              primaryTypographyProps={{
                variant: 'caption',
                sx: {
                  fontFamily: 'monospace',
                  whiteSpace: 'nowrap',
                  overflow: 'hidden',
                  textOverflow: 'ellipsis',
                },
              }}
              secondaryTypographyProps={{ variant: 'caption' }}
            />
            <Tooltip title={entry.success ? 'Succeeded' : (entry.errorMessage ?? 'Failed')}>
              <Chip
                size="small"
                variant="outlined"
                color={entry.success ? 'success' : 'error'}
                label={`${entry.elapsedMillis ?? 0} ms`}
              />
            </Tooltip>
          </ListItemButton>
        ))}
        {history.length === 0 && (
          <Typography variant="caption" color="text.secondary" sx={{ pl: 2 }}>
            No history yet.
          </Typography>
        )}
      </List>
    </Box>
  );
}
