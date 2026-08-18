import type { ReactElement, ReactNode } from 'react';
import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Tooltip from '@mui/material/Tooltip';
import { capabilityState } from './vectorModel';
import type { CapabilityName, ClusterCapabilities } from './types';

export interface CapabilityGateProps {
  capabilities: ClusterCapabilities | undefined | null;
  capability: CapabilityName;
  children: ReactNode;
  /**
   * What to show instead when the cluster cannot do this. Defaults to a disabled explanatory
   * notice — the plan is explicit that unsupported features are hidden *with an explanation*,
   * never shown broken (§7.1).
   */
  fallback?: ReactNode;
}

/**
 * Section 7.1 gate.
 *
 * Vector/ANN exists on Cassandra 5.x and Astra; SAI additionally on DSE 6.8+. **Neither exists on
 * Amazon Keyspaces or ScyllaDB.** The tooltip text is the probe's own `reason`, so it names the
 * detected flavour and version rather than a generic "not supported".
 */
export function CapabilityGate({
  capabilities,
  capability,
  children,
  fallback,
}: CapabilityGateProps): ReactElement {
  const state = capabilityState(capabilities, capability);

  if (!state.supported) {
    return (
      <>
        {fallback ?? (
          <Alert severity="info" variant="outlined" data-testid="capability-unsupported">
            {state.reason}
          </Alert>
        )}
      </>
    );
  }

  if (state.partial) {
    return (
      <Tooltip title={state.reason}>
        <Box data-testid="capability-partial">{children}</Box>
      </Tooltip>
    );
  }

  return <>{children}</>;
}
