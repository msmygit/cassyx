import { useState } from 'react';
import Box from '@mui/material/Box';
import Chip from '@mui/material/Chip';
import Divider from '@mui/material/Divider';
import Stack from '@mui/material/Stack';
import Tab from '@mui/material/Tab';
import Tabs from '@mui/material/Tabs';
import Typography from '@mui/material/Typography';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  AnnQueryBuilderForm,
  CapabilityGate,
  SaiIndexList,
  VectorSparkline,
  buildAnnQuery,
  executeAnnQuery,
  dropSaiIndex,
  listSaiIndexes,
  listVectorColumns,
  vectorQueryKeys,
  type AnnQueryPreview,
  type AnnQueryRequest,
  type ClusterCapabilities,
  type QueryResult,
  type SaiIndex,
} from '../vector';

export interface VectorPanelProps {
  connectionId?: string;
  keyspace?: string;
  table?: string;
  /** From the connect-time probe (plan §7.1). Absent means "unknown", which hides the features. */
  capabilities?: ClusterCapabilities | null;
}

/**
 * Vector / SAI / ANN panel (plan §6) — workstream F.
 *
 * Vector support is first-class in v1, not an afterthought: `vector<float, N>` columns rendered as
 * a sparkline plus a dimension badge, SAI index lifecycle with a similarity-function choice, and
 * an ANN builder producing `SELECT … ORDER BY <col> ANN OF [...] LIMIT k` — hybrid SAI predicates
 * included.
 *
 * Everything here is behind {@link CapabilityGate}: vector/ANN exists on Cassandra 5.x and Astra,
 * SAI additionally on DSE 6.8+, and NEITHER on Amazon Keyspaces or ScyllaDB. Unsupported clusters
 * see the probe's explanation, never a broken feature (plan §7.1).
 */
export function VectorPanel({ connectionId, keyspace, table, capabilities }: VectorPanelProps) {
  const [tab, setTab] = useState(0);
  const [preview, setPreview] = useState<AnnQueryPreview | null>(null);
  const [result, setResult] = useState<QueryResult | null>(null);
  const queryClient = useQueryClient();

  const ready = Boolean(connectionId && keyspace && table);

  const columns = useQuery({
    queryKey: vectorQueryKeys.vectorColumns(connectionId ?? '', keyspace ?? '', table ?? ''),
    queryFn: () => listVectorColumns(connectionId as string, keyspace as string, table as string),
    enabled: ready,
  });

  const indexes = useQuery({
    queryKey: vectorQueryKeys.saiIndexes(connectionId ?? '', keyspace ?? '', table ?? ''),
    queryFn: () => listSaiIndexes(connectionId as string, keyspace as string, table as string),
    enabled: ready,
  });

  const previewMutation = useMutation({
    mutationFn: (request: AnnQueryRequest) => buildAnnQuery(connectionId as string, request),
    onSuccess: setPreview,
  });

  const runMutation = useMutation({
    mutationFn: (request: AnnQueryRequest) => executeAnnQuery(connectionId as string, request),
    onSuccess: setResult,
  });

  const dropMutation = useMutation({
    mutationFn: (index: SaiIndex) =>
      dropSaiIndex(connectionId as string, keyspace as string, table as string, index.name),
    onSuccess: () =>
      queryClient.invalidateQueries({
        queryKey: vectorQueryKeys.saiIndexes(connectionId ?? '', keyspace ?? '', table ?? ''),
      }),
  });

  if (!ready) {
    return (
      <Box sx={{ p: 2 }} data-testid="vector-panel">
        <Typography variant="body2" color="text.secondary">
          Select a table to work with its vector columns, SAI indexes and ANN queries.
        </Typography>
      </Box>
    );
  }

  return (
    <Box
      data-testid="vector-panel"
      sx={{ height: '100%', minHeight: 0, display: 'flex', flexDirection: 'column', p: 2 }}
    >
      <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 1 }}>
        <Typography variant="h6">Vector &amp; ANN</Typography>
        <Chip size="small" variant="outlined" label={`${keyspace}.${table}`} />
      </Stack>

      <Tabs value={tab} onChange={(_, next: number) => setTab(next)} sx={{ mb: 2 }}>
        <Tab label="ANN query" />
        <Tab label="Vector columns" />
        <Tab label="SAI indexes" />
      </Tabs>

      {tab === 0 && (
        <CapabilityGate capabilities={capabilities} capability="vector">
          <AnnQueryBuilderForm
            keyspace={keyspace as string}
            table={table as string}
            columns={columns.data ?? []}
            preview={preview}
            onPreview={(request) => previewMutation.mutate(request)}
            onRun={(request) => runMutation.mutate(request)}
          />
          {result && (
            <>
              <Divider sx={{ my: 2 }} />
              <Typography variant="body2" data-testid="ann-result-summary">
                {result.rowCount} row{result.rowCount === 1 ? '' : 's'} in {result.elapsedMillis}ms
                {result.similarityColumns && result.similarityColumns.length > 0
                  ? ` — score columns: ${result.similarityColumns.join(', ')}`
                  : ''}
              </Typography>
            </>
          )}
        </CapabilityGate>
      )}

      {tab === 1 && (
        <CapabilityGate capabilities={capabilities} capability="vector">
          <Stack spacing={1} data-testid="vector-column-list">
            {(columns.data ?? []).length === 0 && (
              <Typography variant="body2" color="text.secondary">
                No <code>vector&lt;float, N&gt;</code> columns on this table.
              </Typography>
            )}
            {(columns.data ?? []).map((column) => (
              <Stack key={column.name} direction="row" spacing={2} alignItems="center">
                <Typography variant="body2" sx={{ fontFamily: 'monospace', minWidth: 160 }}>
                  {column.name}
                </Typography>
                <Typography variant="caption" color="text.secondary" sx={{ minWidth: 160 }}>
                  {column.cqlType ?? `vector<float, ${column.dimensions}>`}
                </Typography>
                <VectorSparkline values={[]} dimensions={column.dimensions} />
                <Chip
                  size="small"
                  variant="outlined"
                  color={column.index ? 'success' : 'default'}
                  label={column.index ? `SAI: ${column.index.name}` : 'no SAI index'}
                />
              </Stack>
            ))}
          </Stack>
        </CapabilityGate>
      )}

      {tab === 2 && (
        <CapabilityGate capabilities={capabilities} capability="sai">
          <SaiIndexList
            indexes={indexes.data ?? []}
            onDrop={(index) => dropMutation.mutate(index)}
          />
        </CapabilityGate>
      )}
    </Box>
  );
}
