import type { ReactElement, ReactNode } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, type RenderOptions, type RenderResult } from '@testing-library/react';
import { CassyxThemeProvider } from '../theme/CassyxThemeProvider';
import type { ColorModePreference } from '../theme/colorMode';

export function createTestQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: 0, staleTime: 0 },
      mutations: { retry: false },
    },
  });
}

export interface RenderWithProvidersOptions extends Omit<RenderOptions, 'wrapper'> {
  queryClient?: QueryClient;
  colorMode?: ColorModePreference;
}

/** Render a component inside the app's real provider stack (theme + react-query). */
export function renderWithProviders(
  ui: ReactElement,
  options: RenderWithProvidersOptions = {},
): RenderResult & { queryClient: QueryClient } {
  const { queryClient = createTestQueryClient(), colorMode = 'light', ...rest } = options;

  const Wrapper = ({ children }: { children: ReactNode }) => (
    <CassyxThemeProvider initialPreference={colorMode}>
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    </CassyxThemeProvider>
  );

  return { ...render(ui, { wrapper: Wrapper, ...rest }), queryClient };
}
