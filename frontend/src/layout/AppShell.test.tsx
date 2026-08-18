import { describe, expect, it } from 'vitest';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useRoutes } from 'react-router';
import { renderWithProviders } from '../test/render';
import { LicenseGate } from '../license/LicenseGate';
import { WorkspaceProvider } from './WorkspaceProvider';
import { routes } from '../routes/routes';
import { placeholderCatalog } from '../schema/placeholderCatalog';
import type { ConnectionSummary, LicenseStatus } from '../api/types';

const licensed: LicenseStatus = {
  licensed: true,
  enforce: true,
  bypass: false,
  edition: 'standard',
};
const bypassed: LicenseStatus = {
  licensed: false,
  enforce: false,
  bypass: true,
  edition: 'unlicensed-bypass',
};

const connections: ConnectionSummary[] = [
  {
    id: 'conn-1',
    name: 'local cluster',
    mode: 'CASSANDRA',
    hasPassword: true,
    createdAt: '2026-08-17T09:00:00Z',
  },
];

/**
 * Mounts the real route table declaratively. The data router (`createBrowserRouter`) builds a
 * `Request` on every navigation, and jsdom's `AbortSignal` is not accepted by Node's `fetch`
 * implementation — an environment artefact, not an app one. `useRoutes` exercises the same
 * route configuration without it.
 */
function Routes() {
  return useRoutes(routes);
}

function renderShell(status: LicenseStatus = licensed, initialEntries = ['/']) {
  return renderWithProviders(
    <LicenseGate statusOverride={status}>
      <WorkspaceProvider
        live={false}
        initialConnections={connections}
        initialSchema={placeholderCatalog()}
      >
        <MemoryRouter initialEntries={initialEntries}>
          <Routes />
        </MemoryRouter>
      </WorkspaceProvider>
    </LicenseGate>,
  );
}

describe('AppShell', () => {
  it('lays out the connection bar, sidebar, tab strip and work area', () => {
    renderShell();

    expect(screen.getByTestId('connection-bar')).toBeInTheDocument();
    expect(screen.getByTestId('schema-sidebar')).toBeInTheDocument();
    expect(screen.getByTestId('schema-tree')).toBeInTheDocument();
    expect(screen.getByTestId('tab-bar')).toBeInTheDocument();
    expect(screen.getByTestId('query-workspace')).toBeInTheDocument();
    expect(screen.getByTestId('query-editor')).toBeInTheDocument();
  });

  it('shows the bypass banner above the whole shell in bypass mode', () => {
    renderShell(bypassed);
    expect(screen.getByTestId('license-bypass-banner')).toBeInTheDocument();
    expect(screen.getByTestId('status-edition')).toHaveTextContent('unlicensed-bypass');
  });

  it('has no bypass banner for a real license', () => {
    renderShell();
    expect(screen.queryByTestId('license-bypass-banner')).not.toBeInTheDocument();
    expect(screen.getByTestId('status-edition')).toHaveTextContent('standard');
  });

  it('opens a table from the schema tree into a new tab, titled by its qualified name', async () => {
    const user = userEvent.setup();
    renderShell();

    await user.click(screen.getByText('demo'));
    await user.dblClick(screen.getByText('orders'));

    const tabBar = screen.getByTestId('tab-bar');
    expect(within(tabBar).getByText('demo.orders')).toBeInTheDocument();
  }, 20_000);

  it('opens same-named tables from different keyspaces as separate tabs', async () => {
    const user = userEvent.setup();
    renderShell();

    await user.click(screen.getByRole('checkbox', { name: /show system keyspaces/i }));
    await user.click(screen.getByText('demo'));
    await user.dblClick(screen.getByText('users'));
    await user.click(screen.getByText('system_auth'));

    const systemUsers = screen
      .getAllByText('users')
      .find((element) => element.closest('[data-identity="system_auth.users"]'));
    await user.dblClick(systemUsers!);

    const tabBar = screen.getByTestId('tab-bar');
    expect(within(tabBar).getByText('demo.users')).toBeInTheDocument();
    expect(within(tabBar).getByText('system_auth.users')).toBeInTheDocument();
  }, 20_000);

  it('adds and closes query tabs', async () => {
    const user = userEvent.setup();
    renderShell();

    const tabBar = screen.getByTestId('tab-bar');
    expect(within(tabBar).getByText('Query 1')).toBeInTheDocument();

    await user.click(within(tabBar).getByRole('button', { name: /new query tab/i }));
    expect(within(tabBar).getByText('Query 2')).toBeInTheDocument();

    await user.click(within(tabBar).getByRole('button', { name: /close query 2/i }));
    expect(within(tabBar).queryByText('Query 2')).not.toBeInTheDocument();
  }, 20_000);

  it('routes to the jobs and vector panels', async () => {
    const user = userEvent.setup();
    renderShell();

    await user.click(screen.getByRole('button', { name: /^jobs$/i }));
    expect(await screen.findByTestId('jobs-panel-empty')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /vector & ann/i }));
    expect(await screen.findByTestId('vector-panel')).toBeInTheDocument();
  }, 20_000);

  it('renders a not-found page for an unknown route', () => {
    renderShell(licensed, ['/nope']);
    expect(screen.getByText(/nothing here/i)).toBeInTheDocument();
  });

  it('resizes the sidebar with the keyboard', async () => {
    const user = userEvent.setup();
    renderShell();

    const handle = screen.getByTestId('sidebar-resize-handle');
    const before = Number(handle.getAttribute('aria-valuenow'));

    handle.focus();
    await user.keyboard('{ArrowRight}');
    expect(Number(handle.getAttribute('aria-valuenow'))).toBe(before + 16);

    await user.keyboard('{ArrowLeft}{ArrowLeft}');
    expect(Number(handle.getAttribute('aria-valuenow'))).toBe(before - 16);
  });

  it('lists connections and toggles the colour mode from the connection bar', async () => {
    const user = userEvent.setup();
    renderShell();

    expect(screen.getByTestId('connection-status')).toHaveTextContent(/not connected/i);
    await user.click(screen.getByTestId('color-mode-toggle'));
    expect(screen.getByRole('button', { name: /toggle colour mode/i })).toBeInTheDocument();
  }, 20_000);
});
