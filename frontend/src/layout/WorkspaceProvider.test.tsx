import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { ReactNode } from 'react';
import { QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react';
import { createTestQueryClient } from '../test/render';
import type { ApiSchemaTree } from '../schema/schemaTypes';
import type { ClusterCapabilities, ConnectionSummary } from '../api/types';
import { WorkspaceProvider, type WorkspaceProviderProps } from './WorkspaceProvider';
import type * as ConnectionsApi from '../connections/connectionsApi';
import type * as SchemaApi from '../schema/schemaApi';
import { useWorkspace } from './workspaceContext';

const listConnections = vi.fn();
const getConnectionHealth = vi.fn();
const getClusterCapabilities = vi.fn();
const getSchemaTree = vi.fn();

vi.mock('../connections/connectionsApi', async (importOriginal) => ({
  ...(await importOriginal<typeof ConnectionsApi>()),
  listConnections: (...args: unknown[]) => listConnections(...args) as unknown,
  getConnectionHealth: (...args: unknown[]) => getConnectionHealth(...args) as unknown,
  getClusterCapabilities: (...args: unknown[]) => getClusterCapabilities(...args) as unknown,
}));

vi.mock('../schema/schemaApi', async (importOriginal) => ({
  ...(await importOriginal<typeof SchemaApi>()),
  getSchemaTree: (...args: unknown[]) => getSchemaTree(...args) as unknown,
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

/**
 * `demo.users` AND `system_auth.users`: same table name, different keyspaces. Every assertion
 * about identity below depends on this collision being present.
 */
function tree(): ApiSchemaTree {
  return {
    keyspaces: [
      {
        label: 'demo',
        kind: 'KEYSPACE',
        identity: { kind: 'KEYSPACE', keyspace: 'demo' },
        children: [
          {
            label: 'users',
            kind: 'TABLE',
            identity: { kind: 'TABLE', keyspace: 'demo', table: 'users' },
            children: [
              {
                label: 'user_id',
                kind: 'COLUMN',
                identity: { kind: 'COLUMN', keyspace: 'demo', table: 'users', column: 'user_id' },
                detail: 'uuid | PARTITION_KEY',
              },
              {
                label: 'email',
                kind: 'COLUMN',
                identity: { kind: 'COLUMN', keyspace: 'demo', table: 'users', column: 'email' },
                detail: 'text | REGULAR',
              },
            ],
          },
        ],
      },
      {
        label: 'system_auth',
        kind: 'KEYSPACE',
        identity: { kind: 'KEYSPACE', keyspace: 'system_auth' },
        system: true,
        children: [
          {
            label: 'users',
            kind: 'TABLE',
            identity: { kind: 'TABLE', keyspace: 'system_auth', table: 'users' },
            children: [
              {
                label: 'role',
                kind: 'COLUMN',
                identity: {
                  kind: 'COLUMN',
                  keyspace: 'system_auth',
                  table: 'users',
                  column: 'role',
                },
                detail: 'text | PARTITION_KEY',
              },
            ],
          },
        ],
      },
    ],
  } as ApiSchemaTree;
}

const capabilities: ClusterCapabilities = {
  flavour: 'CASSANDRA',
  probedAt: '2026-08-18T09:00:00Z',
  clusterName: 'Test Cluster',
  releaseVersion: '5.0.2',
  capabilities: {
    sai: { name: 'sai', support: 'SUPPORTED' },
    vector: { name: 'vector', support: 'SUPPORTED' },
    materializedViews: { name: 'materializedViews', support: 'PARTIAL' },
    dseSearch: {
      name: 'dseSearch',
      support: 'UNSUPPORTED',
      reason: 'DSE Search is only available on DataStax Enterprise.',
    },
  },
};

function renderWorkspace(props: Omit<WorkspaceProviderProps, 'children'> = {}) {
  const queryClient = createTestQueryClient();
  return renderHook(() => useWorkspace(), {
    wrapper: ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={queryClient}>
        <WorkspaceProvider {...props}>{children}</WorkspaceProvider>
      </QueryClientProvider>
    ),
  });
}

beforeEach(() => {
  vi.clearAllMocks();
  listConnections.mockResolvedValue([
    connection({ id: 'conn-0', name: 'idle' }),
    connection({ id: 'conn-1', name: 'live', connected: true, defaultKeyspace: 'demo' }),
  ]);
  getConnectionHealth.mockResolvedValue({ connectionId: 'conn-1', status: 'CONNECTED' });
  getClusterCapabilities.mockResolvedValue(capabilities);
  getSchemaTree.mockResolvedValue(tree());
});

describe('WorkspaceProvider (live)', () => {
  it('loads connections and adopts the one that already has a session', async () => {
    const { result } = renderWorkspace();

    await waitFor(() => expect(result.current.activeConnectionId).toBe('conn-1'));
    expect(result.current.connections).toHaveLength(2);
    expect(result.current.activeConnection?.defaultKeyspace).toBe('demo');
    expect(listConnections).toHaveBeenCalled();
  });

  it('derives CONNECTED from the health endpoint, not from static state', async () => {
    const { result } = renderWorkspace();
    await waitFor(() => expect(result.current.status).toBe('CONNECTED'));

    expect(getConnectionHealth).toHaveBeenCalledWith('conn-1');
  });

  it('reports ERROR when the health probe fails', async () => {
    getConnectionHealth.mockRejectedValue(new Error('no route to host'));
    const { result } = renderWorkspace();

    await waitFor(() => expect(result.current.status).toBe('ERROR'));
  });

  it('loads the schema tree for the active connection and refetches on the system toggle', async () => {
    const { result } = renderWorkspace();

    await waitFor(() => expect(result.current.schema).toHaveLength(2));
    expect(getSchemaTree).toHaveBeenCalledWith('conn-1', false);

    act(() => result.current.setShowSystem(true));

    await waitFor(() => expect(getSchemaTree).toHaveBeenCalledWith('conn-1', true));
    expect(result.current.showSystem).toBe(true);
  });

  it("resolves the selection from the node's OWN identity, never from tree position", async () => {
    const { result } = renderWorkspace();
    await waitFor(() => expect(result.current.schema).toHaveLength(2));

    const demoUsers = result.current.schema[0]?.children?.[0];
    const systemUsers = result.current.schema[1]?.children?.[0];
    expect(demoUsers?.id).not.toBe(systemUsers?.id);

    act(() => result.current.setSelectedNodeId(demoUsers!.id));
    await waitFor(() =>
      expect(result.current.selectedTable).toEqual({ keyspace: 'demo', table: 'users' }),
    );

    act(() => result.current.setSelectedNodeId(systemUsers!.id));
    await waitFor(() =>
      expect(result.current.selectedTable).toEqual({ keyspace: 'system_auth', table: 'users' }),
    );
  });

  it('keys the completion schema by qualified name so same-named tables stay distinct', async () => {
    const { result } = renderWorkspace();

    await waitFor(() => expect(result.current.schema).toHaveLength(2));
    expect(result.current.completionSchema).toEqual({
      'demo.users': ['user_id', 'email'],
      'system_auth.users': ['role'],
    });
  });

  it('exposes the probe and the capability names it reports as usable', async () => {
    const { result } = renderWorkspace();

    await waitFor(() => expect(result.current.capabilities?.clusterName).toBe('Test Cluster'));
    expect(result.current.capabilityNames).toEqual(
      expect.arrayContaining(['sai', 'vector', 'materializedViews']),
    );
    // PARTIAL counts as usable; UNSUPPORTED does not.
    expect(result.current.capabilityNames).not.toContain('dseSearch');
  });

  it('drops a selection whose connection has been deleted', async () => {
    const { result } = renderWorkspace();
    await waitFor(() => expect(result.current.activeConnectionId).toBe('conn-1'));

    act(() => result.current.setActiveConnectionId('conn-gone'));
    await waitFor(() => expect(result.current.activeConnectionId).toBe('conn-1'));
  });
});

describe('WorkspaceProvider (offline)', () => {
  it('issues no requests and renders empty state when live is false', async () => {
    const { result } = renderWorkspace({ live: false });

    await waitFor(() => expect(result.current.status).toBe('DISCONNECTED'));
    expect(result.current.connections).toEqual([]);
    expect(result.current.schema).toEqual([]);
    expect(result.current.selectedTable).toBeNull();
    expect(result.current.completionSchema).toEqual({});
    expect(result.current.capabilityNames).toBeUndefined();
    expect(listConnections).not.toHaveBeenCalled();
    expect(getSchemaTree).not.toHaveBeenCalled();
  });

  it('never queries the schema with no connection selected', async () => {
    listConnections.mockResolvedValue([]);
    const { result } = renderWorkspace();

    await waitFor(() => expect(result.current.connections).toEqual([]));
    expect(result.current.activeConnectionId).toBeNull();
    expect(result.current.status).toBe('DISCONNECTED');
    expect(getSchemaTree).not.toHaveBeenCalled();
    expect(getClusterCapabilities).not.toHaveBeenCalled();
  });
});
