import { describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '../test/render';
import { ConnectionDialog } from './ConnectionDialog';

describe('ConnectionDialog', () => {
  it('offers exactly the three connection modes from the plan', () => {
    renderWithProviders(<ConnectionDialog open onClose={vi.fn()} />);
    const tabs = screen.getAllByRole('tab');
    expect(tabs.map((tab) => tab.textContent)).toEqual(['Cassandra / DSE', 'Astra DB', 'Advanced']);
  });

  it('masks the Cassandra password too', () => {
    renderWithProviders(<ConnectionDialog open onClose={vi.fn()} />);
    expect(screen.getByTestId('cassandra-password')).toHaveAttribute('type', 'password');
  });

  it('blocks saving until the form validates and reports why', async () => {
    const user = userEvent.setup();
    const onSave = vi.fn();
    renderWithProviders(<ConnectionDialog open onClose={vi.fn()} onSave={onSave} />);

    await user.click(screen.getByTestId('connection-save'));
    expect(onSave).not.toHaveBeenCalled();
    expect(screen.getByText(/fix the highlighted fields/i)).toBeInTheDocument();
    expect(screen.getByText(/give this connection a name/i)).toBeInTheDocument();
  });

  it('saves a valid Cassandra connection and closes', async () => {
    const user = userEvent.setup();
    const onSave = vi.fn();
    const onClose = vi.fn();
    renderWithProviders(<ConnectionDialog open onClose={onClose} onSave={onSave} />);

    await user.type(screen.getByTestId('connection-name'), 'local');
    await user.click(screen.getByTestId('connection-save'));

    expect(onSave).toHaveBeenCalledWith(
      expect.objectContaining({ name: 'local', mode: 'CASSANDRA' }),
      null,
    );
    expect(onClose).toHaveBeenCalled();
  });

  it('switches to the Astra form, which defaults to auto-download', async () => {
    const user = userEvent.setup();
    renderWithProviders(<ConnectionDialog open onClose={vi.fn()} />);

    await user.click(screen.getByRole('tab', { name: /astra db/i }));
    expect(screen.getByTestId('astra-form')).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: /download automatically/i })).toBeChecked();
  });

  it('switches to the advanced HOCON passthrough', async () => {
    const user = userEvent.setup();
    renderWithProviders(<ConnectionDialog open onClose={vi.fn()} />);

    await user.click(screen.getByRole('tab', { name: /advanced/i }));
    expect(screen.getByTestId('advanced-conf')).toBeInTheDocument();
  });
});
