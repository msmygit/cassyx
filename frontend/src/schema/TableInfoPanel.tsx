import { useEffect, useState } from 'react';
import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Chip from '@mui/material/Chip';
import CircularProgress from '@mui/material/CircularProgress';
import Stack from '@mui/material/Stack';
import Tab from '@mui/material/Tab';
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableHead from '@mui/material/TableHead';
import TableRow from '@mui/material/TableRow';
import Tabs from '@mui/material/Tabs';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import type { SchemaIdentity } from './model';
import { qualifiedName } from './model';
import { useTableInfo, useTableStatistics, useUpdateTableComment } from './schemaQueries';

export interface TableInfoPanelProps {
  connectionId: string;
  /** The table's OWN identity — never derived from tree position (plan §4). */
  identity: SchemaIdentity;
}

const TABS = ['Fields', 'Indexes', 'Comment', 'Definition', 'Statistics'] as const;

/**
 * The table info panel of plan §4, with all five tabs actually populated.
 *
 * The prior-art prototype shipped INDEXES and COMMENT as permanently empty stubs and had a
 * statistics API with no UI at all. Here: indexes come from the driver's metadata, the comment is
 * editable, and statistics render the cached COUNT snapshot — or, when there is none, say so and
 * offer to start a job rather than showing an error.
 */
export function TableInfoPanel({ connectionId, identity }: TableInfoPanelProps) {
  const keyspace = identity.keyspace;
  const table = identity.table;
  const [tab, setTab] = useState(0);

  const info = useTableInfo(connectionId, keyspace, table);
  const statistics = useTableStatistics(connectionId, keyspace, table);
  const updateComment = useUpdateTableComment(connectionId, keyspace, table ?? '');

  const [comment, setComment] = useState('');
  useEffect(() => {
    setComment(info.data?.comment ?? '');
  }, [info.data?.comment]);

  if (!table) {
    return <Alert severity="info">Select a table to see its fields, indexes and definition.</Alert>;
  }

  return (
    <Stack sx={{ height: '100%', minHeight: 0 }} data-testid="table-info-panel">
      <Box sx={{ px: 1, pt: 1 }}>
        <Typography variant="subtitle2" sx={{ fontFamily: 'monospace' }}>
          {qualifiedName(identity)}
        </Typography>
      </Box>
      <Tabs
        value={tab}
        onChange={(_, next: number) => setTab(next)}
        variant="scrollable"
        scrollButtons={false}
        sx={{ minHeight: 36, borderBottom: 1, borderColor: 'chrome.border' }}
      >
        {TABS.map((label) => (
          <Tab key={label} label={label} sx={{ minHeight: 36, py: 0 }} />
        ))}
      </Tabs>

      <Box sx={{ flex: 1, minHeight: 0, overflow: 'auto', p: 1 }}>
        {info.isPending && <CircularProgress size={18} aria-label="Loading table info" />}
        {info.isError && (
          <Alert severity="error">
            {(info.error as Error)?.message ?? 'Could not load table info.'}
          </Alert>
        )}

        {info.data && tab === 0 && (
          <Table size="small" data-testid="fields-tab">
            <TableHead>
              <TableRow>
                <TableCell>Name</TableCell>
                <TableCell>Type</TableCell>
                <TableCell>Kind</TableCell>
                <TableCell>Comment</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {info.data.fields.map((field) => (
                <TableRow key={field.name}>
                  <TableCell sx={{ fontFamily: 'monospace' }}>{field.name}</TableCell>
                  <TableCell sx={{ fontFamily: 'monospace' }}>{field.type}</TableCell>
                  <TableCell>
                    <Chip size="small" label={field.kind} variant="outlined" />
                  </TableCell>
                  <TableCell>{field.comment ?? ''}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}

        {info.data && tab === 1 && (
          <Box data-testid="indexes-tab">
            {info.data.indexes.length === 0 ? (
              <Typography variant="body2" color="text.secondary">
                This table has no indexes.
              </Typography>
            ) : (
              <Table size="small">
                <TableHead>
                  <TableRow>
                    <TableCell>Name</TableCell>
                    <TableCell>Target</TableCell>
                    <TableCell>Kind</TableCell>
                    <TableCell>Options</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {info.data.indexes.map((index) => (
                    <TableRow key={index.name}>
                      <TableCell sx={{ fontFamily: 'monospace' }}>{index.name}</TableCell>
                      <TableCell sx={{ fontFamily: 'monospace' }}>{index.target}</TableCell>
                      <TableCell>
                        <Chip size="small" label={index.kind} variant="outlined" />
                      </TableCell>
                      <TableCell sx={{ fontFamily: 'monospace', fontSize: '0.7rem' }}>
                        {Object.entries(index.options ?? {})
                          .map(([key, value]) => `${key}=${value}`)
                          .join(', ')}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            )}
          </Box>
        )}

        {info.data && tab === 2 && (
          <Stack spacing={1} data-testid="comment-tab">
            <TextField
              multiline
              minRows={3}
              fullWidth
              size="small"
              label="Table comment"
              value={comment}
              onChange={(event) => setComment(event.target.value)}
              slotProps={{ htmlInput: { 'aria-label': 'Table comment' } }}
            />
            <Box sx={{ display: 'flex', justifyContent: 'flex-end', gap: 1 }}>
              <Button
                size="small"
                variant="contained"
                disabled={updateComment.isPending || comment === (info.data.comment ?? '')}
                onClick={() => updateComment.mutate(comment)}
              >
                Save comment
              </Button>
            </Box>
            {updateComment.isError && (
              <Alert severity="error">{(updateComment.error as Error).message}</Alert>
            )}
            {updateComment.isSuccess && (
              <Alert severity="success" data-testid="comment-saved">
                {updateComment.data.executedCql[0]}
              </Alert>
            )}
          </Stack>
        )}

        {info.data && tab === 3 && (
          <Box
            component="pre"
            data-testid="definition-tab"
            sx={{ fontFamily: 'monospace', fontSize: '0.75rem', whiteSpace: 'pre-wrap', m: 0 }}
          >
            {info.data.definition}
          </Box>
        )}

        {tab === 4 && (
          <Box data-testid="statistics-tab">
            {statistics.isPending ? (
              <CircularProgress size={18} aria-label="Loading statistics" />
            ) : statistics.data ? (
              <Stack spacing={1}>
                <Typography variant="body2">
                  {statistics.data.totalRows.toLocaleString()} rows
                  {statistics.data.partitionCount
                    ? ` across ${statistics.data.partitionCount.toLocaleString()} partitions`
                    : ''}
                </Typography>
                <Typography variant="caption" color="text.secondary">
                  Computed {statistics.data.computedAt} by job {statistics.data.jobId}
                </Typography>
                {(statistics.data.largestPartitions ?? []).length > 0 && (
                  <Table size="small">
                    <TableHead>
                      <TableRow>
                        <TableCell>Largest partitions</TableCell>
                        <TableCell align="right">Rows</TableCell>
                      </TableRow>
                    </TableHead>
                    <TableBody>
                      {(statistics.data.largestPartitions ?? []).map((partition) => (
                        <TableRow key={partition.partitionKey}>
                          <TableCell sx={{ fontFamily: 'monospace' }}>
                            {partition.partitionKey}
                          </TableCell>
                          <TableCell align="right">{partition.rows.toLocaleString()}</TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                )}
              </Stack>
            ) : (
              <Alert severity="info" data-testid="statistics-empty">
                No statistics have been computed for this table yet. Start a COUNT job to populate
                row estimates and the largest-partition list.
              </Alert>
            )}
          </Box>
        )}
      </Box>
    </Stack>
  );
}
