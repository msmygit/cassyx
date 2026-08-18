import { createContext, useContext } from 'react';
import type { ConnectionSummary, SessionStatus } from '../api/types';
import type { SchemaNode } from '../schema/model';

/**
 * Cross-cutting shell state: connections, session status, the schema catalog and tree selection.
 * Kept in its own module so `WorkspaceProvider.tsx` exports a component and nothing else.
 */
export interface WorkspaceContextValue {
  connections: ConnectionSummary[];
  activeConnectionId: string | null;
  setActiveConnectionId: (id: string | null) => void;
  status: SessionStatus;
  setStatus: (status: SessionStatus) => void;
  schema: SchemaNode[];
  selectedNodeId: string | null;
  setSelectedNodeId: (id: string | null) => void;
}

export const WorkspaceContext = createContext<WorkspaceContextValue | null>(null);

export function useWorkspace(): WorkspaceContextValue {
  const context = useContext(WorkspaceContext);
  if (!context) throw new Error('useWorkspace must be used inside <WorkspaceProvider>');
  return context;
}
