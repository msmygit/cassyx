import { VectorPanel } from '../panels/VectorPanel';
import { useWorkspace } from '../layout/workspaceContext';

/**
 * Vector / SAI / ANN route (plan §6), fed from the active connection and the selected node's own
 * `{keyspace, table}`.
 *
 * The panel is internally gated on the §7.1 probe: an unsupported cluster gets the probe's own
 * explanation, never a broken feature. Passing `capabilities` through is what makes that gate real.
 */
export function VectorPage() {
  const workspace = useWorkspace();
  const selected = workspace.selectedTable;

  return (
    <VectorPanel
      {...(workspace.activeConnectionId ? { connectionId: workspace.activeConnectionId } : {})}
      {...(selected ? { keyspace: selected.keyspace, table: selected.table } : {})}
      capabilities={workspace.capabilities}
    />
  );
}
