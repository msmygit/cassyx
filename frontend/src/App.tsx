import { useState } from 'react';
import { QueryClientProvider } from '@tanstack/react-query';
import { createQueryClient } from './api/queryClient';
import { LicenseGate } from './license/LicenseGate';
import { WorkspaceProvider } from './layout/WorkspaceProvider';
import { AppRouter } from './routes/router';
import { CassyxThemeProvider } from './theme/CassyxThemeProvider';

/**
 * Provider stack, outermost first:
 *
 *   theme → react-query → LicenseGate → workspace state → router
 *
 * The license gate sits ABOVE the workspace and the router deliberately: an unlicensed instance
 * renders the activation screen and never mounts the shell, so no feature query is ever issued
 * behind the gate.
 */
export function App() {
  const [queryClient] = useState(createQueryClient);

  return (
    <CassyxThemeProvider>
      <QueryClientProvider client={queryClient}>
        <LicenseGate>
          <WorkspaceProvider>
            <AppRouter />
          </WorkspaceProvider>
        </LicenseGate>
      </QueryClientProvider>
    </CassyxThemeProvider>
  );
}
