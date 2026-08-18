import { describe, expect, it, vi } from 'vitest';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { renderWithProviders } from '../test/render';
import { placeholderCatalog } from '../schema/placeholderCatalog';
import type { ClusterCapabilities, ConnectionSummary } from '../api/types';
import { SchemaSidebar } from './SchemaSidebar';
import { WorkspaceProvider } from './WorkspaceProvider';

const connections: ConnectionSummary[] = [
  {
    id: 'conn-1',
    name: 'local cluster',
    mode: 'CASSANDRA',
    hasPassword: false,
    createdAt: '2026-08-18T09:00:00Z',
  },
];

/** Amazon Keyspaces shape: no MVs, no UDF/UDA, no roles — the §7.1 gating case. */
const keyspacesLike: ClusterCapabilities = {
  flavour: 'AMAZON_KEYSPACES',
  probedAt: '2026-08-18T09:00:00Z',
  capabilities: {
    sai: { name: 'sai', support: 'UNSUPPORTED' },
    vector: { name: 'vector', support: 'UNSUPPORTED' },
    materializedViews: { name: 'materializedViews', support: 'UNSUPPORTED' },
    udfUda: { name: 'udfUda', support: 'UNSUPPORTED' },
    rolesPermissions: { name: 'rolesPermissions', support: 'UNSUPPORTED' },
    truncate: { name: 'truncate', support: 'UNSUPPORTED' },
    tokenRangeScan: { name: 'tokenRangeScan', support: 'UNSUPPORTED' },
    lwt: { name: 'lwt', support: 'SUPPORTED' },
    counters: { name: 'counters', support: 'SUPPORTED' },
  },
};

function renderSidebar(options: { connected?: boolean; capabilities?: ClusterCapabilities } = {}) {
  const onOpenInEditor = vi.fn();
  const result = renderWithProviders(
    <MemoryRouter>
      <WorkspaceProvider
        live={false}
        initialSchema={placeholderCatalog()}
        {...(options.connected === false ? {} : { initialConnections: connections })}
        {...(options.capabilities ? { initialCapabilities: options.capabilities } : {})}
      >
        <SchemaSidebar onOpenInEditor={onOpenInEditor} />
      </WorkspaceProvider>
    </MemoryRouter>,
  );
  return { ...result, onOpenInEditor };
}

describe('SchemaSidebar', () => {
  it('renders with no connection and disables the actions that need one', () => {
    renderSidebar({ connected: false });

    expect(screen.getByTestId('schema-tree')).toBeInTheDocument();
    expect(screen.getByTestId('new-object')).toBeDisabled();
    expect(screen.getByTestId('refresh-schema')).toBeDisabled();
  });

  it('offers every object type when the cluster has not been fingerprinted', async () => {
    const user = userEvent.setup();
    renderSidebar();

    await user.click(screen.getByTestId('new-object'));
    const menu = await screen.findByTestId('new-object-menu');

    // Unknown capabilities means "let the user try", not "hide half the product".
    expect(within(menu).getByTestId('new-object-TABLE')).toBeInTheDocument();
    expect(within(menu).getByTestId('new-object-MATERIALIZED_VIEW')).toBeInTheDocument();
    expect(within(menu).getByTestId('new-object-FUNCTION')).toBeInTheDocument();
  });

  it('hides object types the probe says the cluster cannot manage (§7.1)', async () => {
    const user = userEvent.setup();
    renderSidebar({ capabilities: keyspacesLike });

    await user.click(screen.getByTestId('new-object'));
    const menu = await screen.findByTestId('new-object-menu');

    expect(within(menu).getByTestId('new-object-TABLE')).toBeInTheDocument();
    expect(within(menu).queryByTestId('new-object-MATERIALIZED_VIEW')).not.toBeInTheDocument();
    expect(within(menu).queryByTestId('new-object-FUNCTION')).not.toBeInTheDocument();
    expect(within(menu).queryByTestId('new-object-ROLE')).not.toBeInTheDocument();
  });

  it('opens the DDL editor from the "New object" menu', async () => {
    const user = userEvent.setup();
    renderSidebar();

    await user.click(screen.getByTestId('new-object'));
    await user.click(await screen.findByTestId('new-object-KEYSPACE'));

    expect(await screen.findByRole('dialog')).toBeInTheDocument();
  });

  it("offers Alter / Truncate / Drop on a table, carrying that node's own identity", async () => {
    const user = userEvent.setup();
    renderSidebar();

    await user.click(screen.getByText('demo'));
    const orders = screen.getByText('orders');
    await user.pointer({ keys: '[MouseRight]', target: orders });

    expect(screen.getByTestId('context-menu-identity')).toHaveTextContent('demo.orders');
    expect(screen.getByTestId('context-menu-alter')).toBeInTheDocument();
    expect(screen.getByTestId('context-menu-truncate')).toBeInTheDocument();
    expect(screen.getByTestId('context-menu-drop')).toBeInTheDocument();

    await user.click(screen.getByTestId('context-menu-drop'));
    expect(await screen.findByRole('dialog')).toBeInTheDocument();
  });

  it('never offers TRUNCATE on a keyspace', async () => {
    const user = userEvent.setup();
    renderSidebar();

    await user.pointer({ keys: '[MouseRight]', target: screen.getByText('demo') });

    expect(screen.getByTestId('context-menu-alter')).toBeInTheDocument();
    expect(screen.queryByTestId('context-menu-truncate')).not.toBeInTheDocument();
  });

  it('lifts the show-system toggle into the workspace so the query can see it', async () => {
    const user = userEvent.setup();
    renderSidebar();

    const toggle = screen.getByRole('checkbox', { name: /show system keyspaces/i });
    expect(toggle).not.toBeChecked();

    await user.click(toggle);
    expect(screen.getByRole('checkbox', { name: /show system keyspaces/i })).toBeChecked();
    expect(screen.getByText('system_auth')).toBeInTheDocument();
  });

  it('offers a "Load data into…" entry on tables only', async () => {
    const user = userEvent.setup();
    renderSidebar();

    await user.click(screen.getByText('demo'));
    await user.pointer({ keys: '[MouseRight]', target: screen.getByText('orders') });
    expect(screen.getByTestId('context-menu-load')).toBeInTheDocument();

    await user.keyboard('{Escape}');
    await user.pointer({ keys: '[MouseRight]', target: screen.getByText('demo') });
    expect(screen.queryByTestId('context-menu-load')).not.toBeInTheDocument();
  });
});
