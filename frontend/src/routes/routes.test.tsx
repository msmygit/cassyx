import { beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useRoutes } from 'react-router';
import { renderWithProviders } from '../test/render';
import { LicenseGate } from '../license/LicenseGate';
import { WorkspaceProvider } from '../layout/WorkspaceProvider';
import { placeholderCatalog } from '../schema/placeholderCatalog';
import type * as SchemaApi from '../schema/schemaApi';
import type * as DsbulkApi from '../bulk/dsbulk/dsbulkApi';
import { routes } from './routes';
import type { ClusterCapabilities, ConnectionSummary, LicenseStatus } from '../api/types';

const getTableInfo = vi.fn();
const getTableStatistics = vi.fn();

vi.mock('../schema/schemaApi', async (importOriginal) => ({
  ...(await importOriginal<typeof SchemaApi>()),
  getTableInfo: (...args: unknown[]) => getTableInfo(...args) as unknown,
  getTableStatistics: (...args: unknown[]) => getTableStatistics(...args) as unknown,
}));

vi.mock('../bulk/dsbulk/dsbulkApi', async (importOriginal) => ({
  ...(await importOriginal<typeof DsbulkApi>()),
  listJobTemplates: async () => [],
}));

const licensed: LicenseStatus = {
  licensed: true,
  enforce: true,
  bypass: false,
  edition: 'standard',
};

const connections: ConnectionSummary[] = [
  {
    id: 'conn-1',
    name: 'local cluster',
    mode: 'CASSANDRA',
    hasPassword: false,
    createdAt: '2026-08-18T09:00:00Z',
  },
];

function Routes() {
  return useRoutes(routes);
}

interface ShellOptions {
  entries?: string[];
  connected?: boolean;
  capabilities?: ClusterCapabilities;
}

function renderShell({ entries = ['/'], connected = true, capabilities }: ShellOptions = {}) {
  return renderWithProviders(
    <LicenseGate statusOverride={licensed}>
      <WorkspaceProvider
        live={false}
        initialSchema={placeholderCatalog()}
        {...(connected ? { initialConnections: connections } : {})}
        {...(capabilities ? { initialCapabilities: capabilities } : {})}
      >
        <MemoryRouter initialEntries={entries}>
          <Routes />
        </MemoryRouter>
      </WorkspaceProvider>
    </LicenseGate>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  getTableInfo.mockImplementation(async (_id: string, keyspace: string, table: string) => ({
    identity: { kind: 'TABLE', keyspace, table, qualifiedName: `${keyspace}.${table}` },
    fields: [
      {
        identity: { kind: 'COLUMN', keyspace, table, column: 'id' },
        name: 'id',
        type: 'uuid',
        kind: 'PARTITION_KEY',
      },
    ],
    indexes: [],
    definition: `CREATE TABLE ${keyspace}.${table} (id uuid PRIMARY KEY);`,
    statisticsAvailable: false,
  }));
  getTableStatistics.mockResolvedValue(null);
});

describe('workspace route', () => {
  it('renders the connected query workspace, not a disconnected editor/grid pair', () => {
    renderShell();
    expect(screen.getByTestId('query-workspace')).toBeInTheDocument();
  }, 20_000);

  it("mounts the table info panel from the selected node's OWN identity", async () => {
    const user = userEvent.setup();
    renderShell();

    await user.click(screen.getByText('demo'));
    await user.click(screen.getByText('orders'));

    expect(await screen.findByTestId('table-info-aside')).toBeInTheDocument();
    await waitFor(() => expect(getTableInfo).toHaveBeenCalledWith('conn-1', 'demo', 'orders'));
    expect(screen.getByTestId('status-selection')).toHaveTextContent('demo.orders');
  }, 20_000);

  it('asks for system_auth.users, not demo.users, when the system node is the one selected', async () => {
    const user = userEvent.setup();
    renderShell();

    await user.click(screen.getByRole('checkbox', { name: /show system keyspaces/i }));
    await user.click(screen.getByText('system_auth'));

    const systemUsers = screen
      .getAllByText('users')
      .find((element) => element.closest('[data-identity="system_auth.users"]'));
    await user.click(systemUsers!);

    await waitFor(() =>
      expect(getTableInfo).toHaveBeenCalledWith('conn-1', 'system_auth', 'users'),
    );
    expect(getTableInfo).not.toHaveBeenCalledWith('conn-1', 'demo', 'users');
  }, 20_000);

  it('shows no table info panel with nothing selected', () => {
    renderShell();
    expect(screen.queryByTestId('table-info-aside')).not.toBeInTheDocument();
  }, 20_000);
});

describe('jobs, load and statistics routes', () => {
  it('mounts the jobs panel with the template picker and the load entry point', async () => {
    renderShell({ entries: ['/jobs'] });

    expect(await screen.findByTestId('jobs-panel-empty')).toBeInTheDocument();
    expect(screen.getByTestId('job-template-picker')).toBeInTheDocument();
    expect(screen.getByTestId('new-load-job')).toBeInTheDocument();
  }, 20_000);

  it('renders the load form for the connection, pre-filled from the query string', async () => {
    renderShell({ entries: ['/jobs/load?keyspace=demo&table=orders'] });

    expect(await screen.findByTestId('load-job-page')).toBeInTheDocument();
  }, 20_000);

  it('explains itself rather than crashing with no connection', () => {
    renderShell({ entries: ['/jobs/load'], connected: false });
    expect(screen.getByTestId('load-job-empty')).toHaveTextContent(/no connection selected/i);
  }, 20_000);

  it('asks for a table before showing statistics', () => {
    renderShell({ entries: ['/statistics'] });
    expect(screen.getByTestId('statistics-empty')).toHaveTextContent(/no table selected/i);
  }, 20_000);

  it('renders the count statistics view once a table is selected', async () => {
    const user = userEvent.setup();
    renderShell({ entries: ['/'] });

    await user.click(screen.getByText('demo'));
    await user.click(screen.getByText('orders'));
    await user.click(screen.getByRole('button', { name: /^statistics$/i }));

    expect(await screen.findByTestId('statistics-page')).toBeInTheDocument();
    await waitFor(() =>
      expect(getTableStatistics).toHaveBeenCalledWith('conn-1', 'demo', 'orders'),
    );
  }, 20_000);
});

describe('vector route', () => {
  it('mounts the vector panel', async () => {
    renderShell({ entries: ['/vector'] });
    expect(await screen.findByTestId('vector-panel')).toBeInTheDocument();
  }, 20_000);

  it('hides the nav entry behind the probe explanation on a cluster without vectors', () => {
    renderShell({
      capabilities: {
        flavour: 'AMAZON_KEYSPACES',
        probedAt: '2026-08-18T09:00:00Z',
        capabilities: {
          vector: {
            name: 'vector',
            support: 'UNSUPPORTED',
            reason: 'Vector search requires Cassandra 5.x or Astra.',
          },
          lwt: { name: 'lwt', support: 'SUPPORTED' },
        },
      },
    });

    expect(screen.getByTestId('nav-disabled-Vector & ANN')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /vector & ann/i })).toBeDisabled();
  }, 20_000);
});

describe('unknown routes', () => {
  it('renders the not-found page', () => {
    renderShell({ entries: ['/nope'] });
    expect(screen.getByText(/nothing here/i)).toBeInTheDocument();
  }, 20_000);
});
