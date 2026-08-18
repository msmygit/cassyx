import { createContext, useContext } from 'react';
import type { ClusterCapabilities, ConnectionSummary, SessionStatus } from '../api/types';
import type { CqlCompletionSchema } from '../query/cqlLanguage';
import type { CapabilityName } from '../schema/schemaTypes';
import type { SchemaIdentity, SchemaNode } from '../schema/model';

/**
 * Cross-cutting shell state: connections, session status, the schema catalog and tree selection.
 * Kept in its own module so `WorkspaceProvider.tsx` exports a component and nothing else.
 *
 * Everything here is *derived from the API* by `WorkspaceProvider`; the only in-memory state is
 * the user's own choices (which connection is active, which node is selected, whether system
 * keyspaces are shown).
 */
export interface WorkspaceContextValue {
  /**
   * `false` suspends every network query the shell owns. It exists so tests and static renders
   * (screenshots, E2E fixtures) can mount the whole shell against seeded data.
   */
  live: boolean;

  connections: ConnectionSummary[];
  connectionsLoading: boolean;
  activeConnectionId: string | null;
  setActiveConnectionId: (id: string | null) => void;
  /** The active connection's own record, or `null` when nothing is selected. */
  activeConnection: ConnectionSummary | null;
  status: SessionStatus;
  setStatus: (status: SessionStatus) => void;

  /** Connect-time capability probe (plan §7.1). `null` until it has run. */
  capabilities: ClusterCapabilities | null;
  /**
   * Capability names the cluster reports as `SUPPORTED` or `PARTIAL`. `undefined` means "not
   * probed", which the DDL model reads as "let the user try" rather than "hide everything".
   */
  capabilityNames: CapabilityName[] | undefined;

  schema: SchemaNode[];
  schemaLoading: boolean;
  schemaError: Error | null;
  refreshSchema: () => void;
  /** Lifted out of `SchemaTree` so it can reach `GET …/schema?includeSystem=` (plan §4). */
  showSystem: boolean;
  setShowSystem: (showSystem: boolean) => void;

  selectedNodeId: string | null;
  setSelectedNodeId: (id: string | null) => void;
  /** The selected node itself, so consumers read its OWN identity rather than tree position. */
  selectedNode: SchemaNode | null;
  /**
   * The selected node's own `{keyspace, table}` — resolved from `node.identity` and nothing else.
   *
   * This is the fix for the prior-art bug where dragging `demo.users` produced
   * `SELECT * FROM system_auth.users`: the identity travels with the node, so it can never be
   * re-derived from an ambient selection or a parent row.
   */
  selectedTable: Required<Pick<SchemaIdentity, 'keyspace' | 'table'>> | null;

  /** Fully-qualified `keyspace.table → columns` map for CQL autocomplete (plan §5.1). */
  completionSchema: CqlCompletionSchema;
}

export const WorkspaceContext = createContext<WorkspaceContextValue | null>(null);

export function useWorkspace(): WorkspaceContextValue {
  const context = useContext(WorkspaceContext);
  if (!context) throw new Error('useWorkspace must be used inside <WorkspaceProvider>');
  return context;
}
