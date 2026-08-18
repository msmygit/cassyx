import { useCallback, useEffect, useMemo, useReducer, useState, type ReactNode } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import type { ClusterCapabilities, ConnectionSummary, SessionStatus } from '../api/types';
import {
  useClusterCapabilities,
  useConnectionHealth,
  useConnections,
} from '../connections/useConnections';
import { buildCompletionSchema, type CqlCompletionSchema } from '../query/cqlLanguage';
import { schemaKeys, useSchemaTree } from '../schema/schemaQueries';
import type { CapabilityName } from '../schema/schemaTypes';
import { findNode, type SchemaNode } from '../schema/model';
import { WorkspaceContext, type WorkspaceContextValue } from './workspaceContext';
import { initialTabsState, tabsReducer, TabsContext, type TabsContextValue } from './tabsModel';

export interface WorkspaceProviderProps {
  children: ReactNode;
  /**
   * `false` suspends every query the shell owns and pins the state to the `initial*` seams below.
   * Tests and static renders use it; the app never does.
   */
  live?: boolean;
  /** Seed/override seams, used when `live` is false (and as the pre-fetch value when it is true). */
  initialConnections?: ConnectionSummary[];
  initialSchema?: SchemaNode[];
  initialStatus?: SessionStatus;
  initialCapabilities?: ClusterCapabilities | null;
}

/**
 * Holds the shell's cross-cutting state: connections, session status, the capability probe, the
 * schema catalog and the multi-tab editor state.
 *
 * All four data sources are real API queries (`useConnections`, `useConnectionHealth`,
 * `useClusterCapabilities`, `useSchemaTree`). The `initial*` props are seams for tests and static
 * renders — the placeholder catalog is no longer wired into the running app.
 */
export function WorkspaceProvider({
  children,
  live = true,
  initialConnections,
  initialSchema,
  initialStatus,
  initialCapabilities = null,
}: WorkspaceProviderProps) {
  const queryClient = useQueryClient();
  const [tabsState, dispatch] = useReducer(tabsReducer, undefined, initialTabsState);
  const [activeConnectionId, setActiveConnectionId] = useState<string | null>(
    initialConnections?.[0]?.id ?? null,
  );
  const [statusOverride, setStatus] = useState<SessionStatus | null>(initialStatus ?? null);
  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null);
  const [showSystem, setShowSystem] = useState(false);

  /* ------------------------------------------------------------------------- connections */

  const connectionsQuery = useConnections(live);
  const connections = useMemo<ConnectionSummary[]>(
    () => (live ? (connectionsQuery.data ?? initialConnections ?? []) : (initialConnections ?? [])),
    [live, connectionsQuery.data, initialConnections],
  );

  // The active id is the user's choice, but a first-run user has not made one yet: adopt the
  // connection that already has a live session, falling back to the first saved one.
  useEffect(() => {
    if (activeConnectionId !== null) return;
    const preferred = connections.find((connection) => connection.connected) ?? connections[0];
    if (preferred) setActiveConnectionId(preferred.id);
  }, [activeConnectionId, connections]);

  // A connection that has been deleted must not stay selected, or every downstream query 404s.
  useEffect(() => {
    if (activeConnectionId === null || connections.length === 0) return;
    if (!connections.some((connection) => connection.id === activeConnectionId)) {
      setActiveConnectionId(null);
    }
  }, [activeConnectionId, connections]);

  const activeConnection = useMemo(
    () => connections.find((connection) => connection.id === activeConnectionId) ?? null,
    [connections, activeConnectionId],
  );

  /* ------------------------------------------------------------------- session + probe */

  const queryConnectionId = live && activeConnectionId ? activeConnectionId : null;

  const health = useConnectionHealth(queryConnectionId ?? undefined, live);
  const capabilitiesQuery = useClusterCapabilities(queryConnectionId ?? undefined);

  const derivedStatus = useMemo<SessionStatus>(() => {
    if (!activeConnectionId) return 'DISCONNECTED';
    if (!live) return 'DISCONNECTED';
    if (health.isError) return 'ERROR';
    if (health.data) {
      // DEGRADED still has a usable session — the node detail lives in the bar's tooltip.
      return health.data.status === 'DISCONNECTED' ? 'DISCONNECTED' : 'CONNECTED';
    }
    return health.isPending ? 'CONNECTING' : 'DISCONNECTED';
  }, [activeConnectionId, live, health.isError, health.isPending, health.data]);

  const status = statusOverride ?? derivedStatus;

  const capabilities = live ? (capabilitiesQuery.data ?? initialCapabilities) : initialCapabilities;

  const capabilityNames = useMemo<CapabilityName[] | undefined>(() => {
    const map = capabilities?.capabilities;
    if (!map) return undefined;
    return Object.values(map)
      .filter((entry) => entry.support === 'SUPPORTED' || entry.support === 'PARTIAL')
      .map((entry) => entry.name);
  }, [capabilities]);

  /* ------------------------------------------------------------------------------ schema */

  const schemaQuery = useSchemaTree(queryConnectionId, showSystem);
  const schema = useMemo<SchemaNode[]>(
    () => (live ? (schemaQuery.data ?? initialSchema ?? []) : (initialSchema ?? [])),
    [live, schemaQuery.data, initialSchema],
  );

  const refreshSchema = useCallback(() => {
    if (!activeConnectionId) return;
    void queryClient.invalidateQueries({ queryKey: schemaKeys.all(activeConnectionId) });
  }, [queryClient, activeConnectionId]);

  const selectedNode = useMemo(
    () => (selectedNodeId ? findNode(schema, selectedNodeId) : null),
    [schema, selectedNodeId],
  );

  // Resolved from the node's OWN identity. Never from its position in the tree.
  const selectedTable = useMemo(() => {
    const identity = selectedNode?.identity;
    const table = identity?.table ?? identity?.view;
    if (!identity || !table) return null;
    return { keyspace: identity.keyspace, table };
  }, [selectedNode]);

  const completionSchema = useMemo<CqlCompletionSchema>(
    () => buildCompletionSchema(collectTables(schema)),
    [schema],
  );

  /* ------------------------------------------------------------------------------ value */

  const tabs = useMemo<TabsContextValue>(() => ({ state: tabsState, dispatch }), [tabsState]);

  const workspace = useMemo<WorkspaceContextValue>(
    () => ({
      live,
      connections,
      connectionsLoading: live && connectionsQuery.isPending,
      activeConnectionId,
      setActiveConnectionId,
      activeConnection,
      status,
      setStatus,
      capabilities: capabilities ?? null,
      capabilityNames,
      schema,
      schemaLoading: live && Boolean(queryConnectionId) && schemaQuery.isPending,
      schemaError: (schemaQuery.error as Error | null) ?? null,
      refreshSchema,
      showSystem,
      setShowSystem,
      selectedNodeId,
      setSelectedNodeId,
      selectedNode,
      selectedTable,
      completionSchema,
    }),
    [
      live,
      connections,
      connectionsQuery.isPending,
      activeConnectionId,
      activeConnection,
      status,
      capabilities,
      capabilityNames,
      schema,
      queryConnectionId,
      schemaQuery.isPending,
      schemaQuery.error,
      refreshSchema,
      showSystem,
      selectedNodeId,
      selectedNode,
      selectedTable,
      completionSchema,
    ],
  );

  return (
    <WorkspaceContext.Provider value={workspace}>
      <TabsContext.Provider value={tabs}>{children}</TabsContext.Provider>
    </WorkspaceContext.Provider>
  );
}

/**
 * Flattens the tree into the `{keyspace, table, columns}` triples the CQL completion source wants.
 *
 * Keys stay fully qualified all the way down: an unqualified table name is ambiguous across
 * keyspaces, which is the same confusion behind the prior art's `system_auth.users` bug.
 */
function collectTables(
  nodes: SchemaNode[],
): { keyspace: string; table: string; columns: string[] }[] {
  const out: { keyspace: string; table: string; columns: string[] }[] = [];

  const walk = (node: SchemaNode) => {
    if (node.kind === 'TABLE' || node.kind === 'VIEW') {
      const table = node.identity.table ?? node.identity.view;
      if (table) {
        out.push({
          keyspace: node.identity.keyspace,
          table,
          columns: (node.children ?? [])
            .filter((child) => child.kind === 'COLUMN')
            .map((child) => child.identity.column ?? child.label),
        });
      }
    }
    node.children?.forEach(walk);
  };

  nodes.forEach(walk);
  return out;
}
