import { describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '../test/render';
import { SchemaTree } from './SchemaTree';
import { placeholderCatalog } from './placeholderCatalog';
import { nodeId, SCHEMA_DRAG_MIME } from './model';

describe('SchemaTree', () => {
  it('hides system keyspaces until the toggle is switched on', async () => {
    const user = userEvent.setup();
    renderWithProviders(<SchemaTree nodes={placeholderCatalog()} />);

    expect(screen.getByText('demo')).toBeInTheDocument();
    expect(screen.queryByText('system_auth')).not.toBeInTheDocument();

    await user.click(screen.getByRole('checkbox', { name: /show system keyspaces/i }));
    expect(screen.getByText('system_auth')).toBeInTheDocument();
  });

  it('filters on the search box and reveals matching descendants', async () => {
    const user = userEvent.setup();
    renderWithProviders(<SchemaTree nodes={placeholderCatalog()} />);

    await user.type(screen.getByTestId('schema-search'), 'embedding');
    expect(screen.getByText('product_embeddings')).toBeInTheDocument();
    expect(screen.queryByText('orders')).not.toBeInTheDocument();
  });

  it('shows an empty state for a search with no matches', async () => {
    const user = userEvent.setup();
    renderWithProviders(<SchemaTree nodes={placeholderCatalog()} />);
    await user.type(screen.getByTestId('schema-search'), 'zzzz');
    expect(screen.getByText(/no schema objects match/i)).toBeInTheDocument();
  });

  it('reports the clicked node’s own identity to onSelect', async () => {
    const user = userEvent.setup();
    const onSelect = vi.fn();
    renderWithProviders(<SchemaTree nodes={placeholderCatalog()} onSelect={onSelect} />);

    await user.click(screen.getByText('demo'));
    await user.click(screen.getByText('orders'));

    expect(onSelect).toHaveBeenLastCalledWith(
      expect.objectContaining({ identity: { keyspace: 'demo', table: 'orders' } }),
    );
  });

  it('resolves the drag payload from the dragged node, not from tree position', async () => {
    const user = userEvent.setup();
    renderWithProviders(<SchemaTree nodes={placeholderCatalog()} />);

    // Reveal both `users` tables so the ambiguous name is on screen twice.
    await user.click(screen.getByRole('checkbox', { name: /show system keyspaces/i }));
    await user.click(screen.getByText('demo'));
    await user.click(screen.getByText('system_auth'));

    const demoUsers = screen.getByTestId(
      `node-${nodeId('TABLE', { keyspace: 'demo', table: 'users' })}`,
    );
    const systemUsers = screen.getByTestId(
      `node-${nodeId('TABLE', { keyspace: 'system_auth', table: 'users' })}`,
    );

    expect(demoUsers).toHaveAttribute('data-identity', 'demo.users');
    expect(systemUsers).toHaveAttribute('data-identity', 'system_auth.users');

    const transferred: Record<string, string> = {};
    const dataTransfer = {
      setData: (type: string, value: string) => {
        transferred[type] = value;
      },
      effectAllowed: 'none',
    };

    demoUsers.dispatchEvent(
      Object.assign(new Event('dragstart', { bubbles: true }), { dataTransfer }),
    );

    expect(transferred['text/plain']).toBe('SELECT * FROM demo.users LIMIT 500;');
    expect(transferred['text/plain']).not.toContain('system_auth');
    expect(JSON.parse(transferred[SCHEMA_DRAG_MIME] ?? '{}').identity).toEqual({
      keyspace: 'demo',
      table: 'users',
    });
  });

  it('opens the context menu against the right-clicked node', async () => {
    const user = userEvent.setup();
    renderWithProviders(<SchemaTree nodes={placeholderCatalog()} />);

    await user.click(screen.getByText('demo'));
    await user.pointer({ keys: '[MouseRight]', target: screen.getByText('orders') });

    expect(screen.getByTestId('context-menu-identity')).toHaveTextContent('demo.orders');
  });

  it('opens a table in the editor on double click', async () => {
    const user = userEvent.setup();
    const onOpenInEditor = vi.fn();
    renderWithProviders(
      <SchemaTree nodes={placeholderCatalog()} onOpenInEditor={onOpenInEditor} />,
    );

    await user.click(screen.getByText('demo'));
    await user.dblClick(screen.getByText('orders'));

    expect(onOpenInEditor).toHaveBeenCalledWith(
      expect.objectContaining({ identity: { keyspace: 'demo', table: 'orders' } }),
    );
  });

  it('badges primary key parts and vector columns', async () => {
    const user = userEvent.setup();
    renderWithProviders(<SchemaTree nodes={placeholderCatalog()} />);

    await user.click(screen.getByText('demo'));
    await user.click(screen.getByText('product_embeddings'));

    expect(screen.getAllByText('PK').length).toBeGreaterThan(0);
    expect(screen.getByText('vec 1536')).toBeInTheDocument();
  });
});
