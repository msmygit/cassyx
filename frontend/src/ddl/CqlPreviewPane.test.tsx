import { describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '../test/render';
import { CqlPreviewPane } from './CqlPreviewPane';

describe('CqlPreviewPane (plan §4: always shown, always editable)', () => {
  it('shows the generated CQL and lets the user edit it', async () => {
    const user = userEvent.setup();
    const onCqlChange = vi.fn();
    renderWithProviders(
      <CqlPreviewPane
        cql="CREATE TABLE demo.users (id uuid PRIMARY KEY);"
        onCqlChange={onCqlChange}
        onExecute={vi.fn()}
      />,
    );

    const editor = screen.getByTestId('cql-preview');
    expect(editor).toHaveValue('CREATE TABLE demo.users (id uuid PRIMARY KEY);');
    expect(editor).not.toBeDisabled();

    await user.type(editor, ' -- reviewed');
    expect(onCqlChange).toHaveBeenCalled();
  });

  it('executes exactly what is in the pane', async () => {
    const user = userEvent.setup();
    const onExecute = vi.fn();
    renderWithProviders(
      <CqlPreviewPane cql="DROP TABLE demo.users;" onCqlChange={vi.fn()} onExecute={onExecute} />,
    );

    await user.click(screen.getByTestId('cql-execute'));
    expect(onExecute).toHaveBeenCalledOnce();
  });

  it('refuses to execute an empty pane', () => {
    renderWithProviders(<CqlPreviewPane cql="   " onCqlChange={vi.fn()} onExecute={vi.fn()} />);
    expect(screen.getByTestId('cql-execute')).toBeDisabled();
  });

  it('blocks execution while the form is incomplete and says why', () => {
    renderWithProviders(
      <CqlPreviewPane
        cql=""
        onCqlChange={vi.fn()}
        problems={['A table needs at least one column.']}
        onExecute={vi.fn()}
      />,
    );

    expect(screen.getByText('A table needs at least one column.')).toBeInTheDocument();
    expect(screen.getByTestId('cql-execute')).toBeDisabled();
  });

  it('surfaces generator warnings and errors distinctly', () => {
    renderWithProviders(
      <CqlPreviewPane
        cql="CREATE TABLE demo.t (id uuid PRIMARY KEY);"
        onCqlChange={vi.fn()}
        warnings={['vector<float, N> columns require driver 4.19.0 (CASSJAVA-2).']}
        error="Keyspace demo does not exist."
        loading
        onExecute={vi.fn()}
      />,
    );

    expect(screen.getByTestId('cql-preview-error')).toHaveTextContent(
      'Keyspace demo does not exist.',
    );
    expect(screen.getByText(/CASSJAVA-2/)).toBeInTheDocument();
    expect(screen.getByLabelText('Generating CQL')).toBeInTheDocument();
  });

  it('shows a running state while executing', () => {
    renderWithProviders(
      <CqlPreviewPane cql="DROP TABLE t;" onCqlChange={vi.fn()} executing onExecute={vi.fn()} />,
    );
    expect(screen.getByTestId('cql-execute')).toBeDisabled();
    expect(screen.getByText('Running…')).toBeInTheDocument();
  });
});
