import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '../test/render';
import { DdlEditorDialog } from './DdlEditorDialog';
import * as schemaApi from '../schema/schemaApi';

vi.mock('../schema/schemaApi');

const CONNECTION = '8f2b1c6e-2a55-4f47-9f2a-4c1c3f0d9a11';
const GENERATED = 'CREATE TYPE IF NOT EXISTS demo.address (street text);';

describe('DdlEditorDialog', () => {
  beforeEach(() => {
    vi.mocked(schemaApi.generateDdl).mockResolvedValue({
      cql: GENERATED,
      statements: [GENERATED],
      warnings: [],
    });
    vi.mocked(schemaApi.executeDdl).mockResolvedValue({
      success: true,
      executedCql: [GENERATED],
      statementsExecuted: 1,
      schemaAgreement: true,
    } as never);
  });

  afterEach(() => vi.resetAllMocks());

  it(
    'generates a preview from the form and executes exactly that CQL',
    { timeout: 20000 },
    async () => {
      const user = userEvent.setup();
      const onExecuted = vi.fn();

      renderWithProviders(
        <DdlEditorDialog
          open
          onClose={vi.fn()}
          connectionId={CONNECTION}
          objectType="TYPE"
          action="CREATE"
          target={{ keyspace: 'demo' }}
          initialValue={{ name: 'address', fields: [{ name: 'street', type: 'text' }] }}
          onExecuted={onExecuted}
        />,
      );

      await waitFor(() => expect(screen.getByTestId('cql-preview')).toHaveValue(GENERATED));

      expect(vi.mocked(schemaApi.generateDdl)).toHaveBeenCalledWith(CONNECTION, {
        objectType: 'TYPE',
        action: 'CREATE',
        keyspace: 'demo',
        definition: { name: 'address', fields: [{ name: 'street', type: 'text' }] },
      });

      await user.click(screen.getByTestId('cql-execute'));

      await waitFor(() => expect(screen.getByTestId('ddl-result')).toBeInTheDocument());
      expect(vi.mocked(schemaApi.executeDdl)).toHaveBeenCalledWith(CONNECTION, {
        cql: GENERATED,
        stopOnError: true,
      });
      expect(onExecuted).toHaveBeenCalledOnce();
    },
  );

  /** The user's edit wins: the generator must not clobber reviewed text. */
  it(
    'executes the user’s edited CQL rather than regenerating over it',
    { timeout: 20000 },
    async () => {
      const user = userEvent.setup();
      renderWithProviders(
        <DdlEditorDialog
          open
          onClose={vi.fn()}
          connectionId={CONNECTION}
          objectType="TYPE"
          action="CREATE"
          target={{ keyspace: 'demo' }}
          initialValue={{ name: 'address', fields: [{ name: 'street', type: 'text' }] }}
        />,
      );

      await waitFor(() => expect(screen.getByTestId('cql-preview')).toHaveValue(GENERATED));

      await user.clear(screen.getByTestId('cql-preview'));
      await user.type(screen.getByTestId('cql-preview'), 'CREATE TYPE demo.hand_written (a text);');
      await user.click(screen.getByTestId('cql-execute'));

      await waitFor(() =>
        expect(vi.mocked(schemaApi.executeDdl)).toHaveBeenCalledWith(CONNECTION, {
          cql: 'CREATE TYPE demo.hand_written (a text);',
          stopOnError: true,
        }),
      );
    },
  );

  it('does not generate anything while the form is incomplete', async () => {
    renderWithProviders(
      <DdlEditorDialog
        open
        onClose={vi.fn()}
        connectionId={CONNECTION}
        objectType="TABLE"
        action="CREATE"
        target={{ keyspace: 'demo' }}
      />,
    );

    expect(await screen.findByText('A name is required.')).toBeInTheDocument();
    expect(screen.getByTestId('cql-execute')).toBeDisabled();
    await new Promise((resolve) => setTimeout(resolve, 260));
    expect(vi.mocked(schemaApi.generateDdl)).not.toHaveBeenCalled();
  });

  it('shows a generator failure instead of stale CQL', async () => {
    vi.mocked(schemaApi.generateDdl).mockRejectedValue(new Error('keyspace demo does not exist'));

    renderWithProviders(
      <DdlEditorDialog
        open
        onClose={vi.fn()}
        connectionId={CONNECTION}
        objectType="TYPE"
        action="CREATE"
        target={{ keyspace: 'demo' }}
        initialValue={{ name: 'address', fields: [{ name: 'street', type: 'text' }] }}
      />,
    );

    expect(await screen.findByTestId('cql-preview-error')).toHaveTextContent(
      'keyspace demo does not exist',
    );
  });

  it('hides an unsupported object type behind an explanation instead of erroring', () => {
    renderWithProviders(
      <DdlEditorDialog
        open
        onClose={vi.fn()}
        connectionId={CONNECTION}
        objectType="MATERIALIZED_VIEW"
        action="CREATE"
        target={{ keyspace: 'demo' }}
        capabilities={['sai', 'vector']}
      />,
    );

    expect(screen.getByTestId('capability-unavailable')).toHaveTextContent(/Astra DB/);
    expect(screen.queryByTestId('cql-preview')).not.toBeInTheDocument();
  });

  it('shows the target scope so the user knows what the statement will hit', () => {
    renderWithProviders(
      <DdlEditorDialog
        open
        onClose={vi.fn()}
        connectionId={CONNECTION}
        objectType="INDEX"
        action="CREATE"
        target={{ keyspace: 'demo', table: 'users' }}
      />,
    );

    expect(screen.getByText('demo.users')).toBeInTheDocument();
  });

  it('labels cluster-scoped editors as such', () => {
    renderWithProviders(
      <DdlEditorDialog
        open
        onClose={vi.fn()}
        connectionId={CONNECTION}
        objectType="ROLE"
        action="CREATE"
        target={{ keyspace: 'demo' }}
      />,
    );

    expect(screen.getByText('Cluster-wide')).toBeInTheDocument();
  });
});
