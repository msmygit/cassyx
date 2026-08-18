import { beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '../test/render';
import type { ConnectionSummary } from '../api/types';
import type * as ConnectionsApi from '../connections/connectionsApi';
import { ConnectionBar } from './ConnectionBar';

const listConnections = vi.fn();
const getConnectionHealth = vi.fn();
const connectConnection = vi.fn();
const disconnectConnection = vi.fn();
const deleteConnection = vi.fn();

vi.mock('../connections/connectionsApi', async (importOriginal) => ({
  ...(await importOriginal<typeof ConnectionsApi>()),
  listConnections: (...args: unknown[]) => listConnections(...args) as unknown,
  getConnectionHealth: (...args: unknown[]) => getConnectionHealth(...args) as unknown,
  connectConnection: (...args: unknown[]) => connectConnection(...args) as unknown,
  disconnectConnection: (...args: unknown[]) => disconnectConnection(...args) as unknown,
  deleteConnection: (...args: unknown[]) => deleteConnection(...args) as unknown,
}));

function connection(overrides: Partial<ConnectionSummary> = {}): ConnectionSummary {
  return {
    id: 'conn-1',
    name: 'local cluster',
    mode: 'CASSANDRA',
    hasPassword: false,
    createdAt: '2026-08-18T09:00:00Z',
    ...overrides,
  };
}

beforeEach(() => {
  vi.clearAllMocks();
  listConnections.mockResolvedValue([connection()]);
  getConnectionHealth.mockResolvedValue({ connectionId: 'conn-1', status: 'CONNECTED' });
  connectConnection.mockResolvedValue({ connectionId: 'conn-1', connected: true });
  disconnectConnection.mockResolvedValue({ connectionId: 'conn-1', connected: false });
  deleteConnection.mockResolvedValue(undefined);
});

describe('ConnectionBar', () => {
  it('renders an empty, working bar with no connection selected', () => {
    renderWithProviders(<ConnectionBar activeConnectionId={null} live={false} />);

    expect(screen.getByTestId('connection-bar')).toBeInTheDocument();
    expect(screen.getByTestId('connection-status')).toHaveTextContent(/not connected/i);
    expect(screen.queryByTestId('connect')).not.toBeInTheDocument();
    expect(screen.queryByTestId('disconnect')).not.toBeInTheDocument();
  });

  it('drives the indicator from the live health endpoint', async () => {
    renderWithProviders(<ConnectionBar activeConnectionId="conn-1" />);

    await waitFor(() =>
      expect(screen.getByTestId('connection-status')).toHaveTextContent(/connected/i),
    );
    expect(getConnectionHealth).toHaveBeenCalledWith('conn-1');
    expect(await screen.findByTestId('disconnect')).toBeInTheDocument();
  });

  it('shows a connect action, and connects, when the session is down', async () => {
    const user = userEvent.setup();
    getConnectionHealth.mockResolvedValue({ connectionId: 'conn-1', status: 'DISCONNECTED' });
    renderWithProviders(<ConnectionBar activeConnectionId="conn-1" />);

    await user.click(await screen.findByTestId('connect'));
    await waitFor(() => expect(connectConnection.mock.calls[0]?.[0]).toBe('conn-1'));
  });

  it('lists the live connections in the switcher', async () => {
    renderWithProviders(<ConnectionBar activeConnectionId="conn-1" />);

    await waitFor(() => expect(screen.getByTestId('connection-select')).toHaveValue('conn-1'));
    expect(listConnections).toHaveBeenCalled();
  });

  it('opens the connections manager and disconnects a live session from it', async () => {
    const user = userEvent.setup();
    listConnections.mockResolvedValue([connection({ connected: true })]);
    renderWithProviders(<ConnectionBar activeConnectionId="conn-1" />);

    await user.click(screen.getByRole('button', { name: /manage connections/i }));

    expect(await screen.findByTestId('connections-list')).toBeInTheDocument();
    await user.click(screen.getByTestId('disconnect-conn-1'));
    await waitFor(() => expect(disconnectConnection.mock.calls[0]?.[0]).toBe('conn-1'));
  });

  it('deletes a connection from the manager', async () => {
    const user = userEvent.setup();
    renderWithProviders(<ConnectionBar activeConnectionId="conn-1" />);

    await user.click(screen.getByRole('button', { name: /manage connections/i }));
    await user.click(await screen.findByTestId('delete-conn-1'));

    await waitFor(() => expect(deleteConnection).toHaveBeenCalledWith('conn-1'));
  });

  it('offers the empty state and hands off to the new-connection dialog', async () => {
    const user = userEvent.setup();
    listConnections.mockResolvedValue([]);
    renderWithProviders(<ConnectionBar activeConnectionId={null} />);

    await user.click(screen.getByRole('button', { name: /manage connections/i }));
    expect(await screen.findByTestId('connections-empty')).toBeInTheDocument();

    await user.click(screen.getByTestId('add-connection'));
    expect(await screen.findByTestId('connection-name')).toBeInTheDocument();
  });

  it('opens the new-connection dialog straight from the bar', async () => {
    const user = userEvent.setup();
    renderWithProviders(<ConnectionBar activeConnectionId={null} live={false} />);

    await user.click(screen.getByTestId('new-connection'));
    expect(await screen.findByTestId('connection-name')).toBeInTheDocument();
  });
});
