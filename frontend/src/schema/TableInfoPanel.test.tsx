import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '../test/render';
import { AppError } from '../api/errors';
import { TableInfoPanel } from './TableInfoPanel';
import * as schemaApi from './schemaApi';
import type { TableInfo, TableStatistics } from './schemaTypes';

vi.mock('./schemaApi');

const CONNECTION = '8f2b1c6e-2a55-4f47-9f2a-4c1c3f0d9a11';

const TABLE_INFO = {
  identity: { kind: 'TABLE', keyspace: 'demo', table: 'users', qualifiedName: 'demo.users' },
  fields: [
    {
      identity: { kind: 'COLUMN', keyspace: 'demo', table: 'users', column: 'user_id' },
      name: 'user_id',
      type: 'uuid',
      kind: 'PARTITION_KEY',
    },
    {
      identity: { kind: 'COLUMN', keyspace: 'demo', table: 'users', column: 'tenant_name' },
      name: 'tenant_name',
      type: 'text',
      kind: 'STATIC',
    },
  ],
  indexes: [
    {
      identity: { kind: 'INDEX', keyspace: 'demo', table: 'users', index: 'users_email_idx' },
      name: 'users_email_idx',
      target: 'email',
      kind: 'SAI',
      options: { similarity_function: 'cosine' },
    },
  ],
  comment: 'Application users',
  definition: 'CREATE TABLE demo.users (user_id uuid PRIMARY KEY);',
  statisticsAvailable: false,
} as unknown as TableInfo;

function notFound(): AppError {
  return new AppError('Not found', {
    kind: 'http',
    status: 404,
    problem: {
      type: 'https://cassyx.dev/problems/not-found',
      title: 'Not found',
      status: 404,
    },
  });
}

describe('TableInfoPanel (plan §4)', () => {
  const identity = { keyspace: 'demo', table: 'users' };

  beforeEach(() => {
    vi.mocked(schemaApi.getTableInfo).mockResolvedValue(TABLE_INFO);
    vi.mocked(schemaApi.getTableStatistics).mockRejectedValue(notFound());
    vi.mocked(schemaApi.updateTableComment).mockResolvedValue({
      success: true,
      executedCql: ["ALTER TABLE demo.users WITH comment = 'Edited';"],
      statementsExecuted: 1,
    } as never);
  });

  afterEach(() => vi.resetAllMocks());

  it('populates the FIELDS tab with name, type and kind including static', async () => {
    renderWithProviders(<TableInfoPanel connectionId={CONNECTION} identity={identity} />);

    expect(await screen.findByTestId('fields-tab')).toBeInTheDocument();
    expect(screen.getByText('user_id')).toBeInTheDocument();
    expect(screen.getByText('PARTITION_KEY')).toBeInTheDocument();
    expect(screen.getByText('STATIC')).toBeInTheDocument();
  });

  /** The prior-art prototype left this tab permanently empty. */
  it('actually populates the INDEXES tab', async () => {
    const user = userEvent.setup();
    renderWithProviders(<TableInfoPanel connectionId={CONNECTION} identity={identity} />);
    await screen.findByTestId('fields-tab');

    await user.click(screen.getByRole('tab', { name: 'Indexes' }));

    expect(screen.getByTestId('indexes-tab')).toBeInTheDocument();
    expect(screen.getByText('users_email_idx')).toBeInTheDocument();
    expect(screen.getByText('SAI')).toBeInTheDocument();
    expect(screen.getByText('similarity_function=cosine')).toBeInTheDocument();
  });

  it('makes the COMMENT tab editable and saves it as an ALTER TABLE', async () => {
    const user = userEvent.setup();
    renderWithProviders(<TableInfoPanel connectionId={CONNECTION} identity={identity} />);
    await screen.findByTestId('fields-tab');

    await user.click(screen.getByRole('tab', { name: 'Comment' }));
    const editor = screen.getByLabelText('Table comment');
    expect(editor).toHaveValue('Application users');

    await user.clear(editor);
    await user.type(editor, 'Edited');
    await user.click(screen.getByRole('button', { name: 'Save comment' }));

    await waitFor(() => expect(screen.getByTestId('comment-saved')).toBeInTheDocument());
    expect(vi.mocked(schemaApi.updateTableComment)).toHaveBeenCalledWith(
      CONNECTION,
      'demo',
      'users',
      { comment: 'Edited' },
    );
    expect(screen.getByTestId('comment-saved')).toHaveTextContent(
      "ALTER TABLE demo.users WITH comment = 'Edited';",
    );
  });

  it('shows the describe output on the DEFINITION tab', async () => {
    const user = userEvent.setup();
    renderWithProviders(<TableInfoPanel connectionId={CONNECTION} identity={identity} />);
    await screen.findByTestId('fields-tab');

    await user.click(screen.getByRole('tab', { name: 'Definition' }));
    expect(screen.getByTestId('definition-tab')).toHaveTextContent('CREATE TABLE demo.users');
  });

  it('turns the statistics 404 into an offer to run a COUNT job, not an error', async () => {
    const user = userEvent.setup();
    renderWithProviders(<TableInfoPanel connectionId={CONNECTION} identity={identity} />);
    await screen.findByTestId('fields-tab');

    await user.click(screen.getByRole('tab', { name: 'Statistics' }));
    expect(await screen.findByTestId('statistics-empty')).toHaveTextContent(/COUNT job/);
  });

  it('renders a cached statistics snapshot when one exists', async () => {
    vi.mocked(schemaApi.getTableStatistics).mockResolvedValue({
      identity: { kind: 'TABLE', keyspace: 'demo', table: 'users' },
      totalRows: 10000000,
      partitionCount: 250000,
      computedAt: '2026-08-17T11:02:33Z',
      jobId: '6c8f2a10-b4f9-4a1e-9a12-5f0a7e2d3b44',
      largestPartitions: [{ partitionKey: 'user_id=1', rows: 412339 }],
    } as unknown as TableStatistics);

    const user = userEvent.setup();
    renderWithProviders(<TableInfoPanel connectionId={CONNECTION} identity={identity} />);
    await screen.findByTestId('fields-tab');

    await user.click(screen.getByRole('tab', { name: 'Statistics' }));
    expect(await screen.findByText(/10,000,000 rows/)).toBeInTheDocument();
    expect(screen.getByText('user_id=1')).toBeInTheDocument();
  });

  it('asks for a table rather than guessing one from context', () => {
    renderWithProviders(
      <TableInfoPanel connectionId={CONNECTION} identity={{ keyspace: 'demo' }} />,
    );
    expect(screen.getByText(/Select a table/)).toBeInTheDocument();
    expect(vi.mocked(schemaApi.getTableInfo)).not.toHaveBeenCalled();
  });

  it('reads the table from the node’s OWN identity, never from context', async () => {
    renderWithProviders(
      <TableInfoPanel
        connectionId={CONNECTION}
        identity={{ keyspace: 'system_auth', table: 'users' }}
      />,
    );

    expect(screen.getByText('system_auth.users')).toBeInTheDocument();
    await waitFor(() =>
      expect(vi.mocked(schemaApi.getTableInfo)).toHaveBeenCalledWith(
        CONNECTION,
        'system_auth',
        'users',
      ),
    );
  });

  it('surfaces a load failure instead of rendering empty tabs', async () => {
    vi.mocked(schemaApi.getTableInfo).mockRejectedValue(new Error('boom'));
    renderWithProviders(<TableInfoPanel connectionId={CONNECTION} identity={identity} />);
    expect(await screen.findByText('boom')).toBeInTheDocument();
  });
});
