import { useMemo, useReducer, useState, type ReactNode } from 'react';
import type { ConnectionSummary, SessionStatus } from '../api/types';
import { WorkspaceContext, type WorkspaceContextValue } from './workspaceContext';
import { placeholderCatalog } from '../schema/placeholderCatalog';
import type { SchemaNode } from '../schema/model';
import { initialTabsState, tabsReducer, TabsContext, type TabsContextValue } from './tabsModel';

export interface WorkspaceProviderProps {
  children: ReactNode;
  /** Test seam — Phase 1 workstreams A/B replace these with real queries. */
  initialConnections?: ConnectionSummary[];
  initialSchema?: SchemaNode[];
  initialStatus?: SessionStatus;
}

/**
 * Holds the shell's cross-cutting state: connections, session status, the schema catalog and the
 * multi-tab editor state.
 *
 * Everything here is deliberately local/in-memory for Phase 0. Phase 1 replaces the schema and
 * connection sources with TanStack Query hooks against the real API without changing the
 * consumers.
 */
export function WorkspaceProvider({
  children,
  initialConnections = [],
  initialSchema,
  initialStatus = 'DISCONNECTED',
}: WorkspaceProviderProps) {
  const [tabsState, dispatch] = useReducer(tabsReducer, undefined, initialTabsState);
  const [activeConnectionId, setActiveConnectionId] = useState<string | null>(
    initialConnections[0]?.id ?? null,
  );
  const [status, setStatus] = useState<SessionStatus>(initialStatus);
  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null);
  const [schema] = useState<SchemaNode[]>(() => initialSchema ?? placeholderCatalog());

  const tabs = useMemo<TabsContextValue>(() => ({ state: tabsState, dispatch }), [tabsState]);

  const workspace = useMemo<WorkspaceContextValue>(
    () => ({
      connections: initialConnections,
      activeConnectionId,
      setActiveConnectionId,
      status,
      setStatus,
      schema,
      selectedNodeId,
      setSelectedNodeId,
    }),
    [initialConnections, activeConnectionId, status, schema, selectedNodeId],
  );

  return (
    <WorkspaceContext.Provider value={workspace}>
      <TabsContext.Provider value={tabs}>{children}</TabsContext.Provider>
    </WorkspaceContext.Provider>
  );
}
