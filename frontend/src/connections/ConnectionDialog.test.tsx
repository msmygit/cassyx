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

  /* ------------------------------------------------- live API wiring (workstream A) */

  it('persists through the real save flow when no onSave override is given', async () => {
    const user = userEvent.setup();
    const onClose = vi.fn();
    const saveFn = vi.fn(async () => ({
      connection: { id: 'c1' } as never,
      session: { connectionId: 'c1', connected: true } as never,
      bundleStored: false,
    }));

    renderWithProviders(
      <ConnectionDialog open onClose={onClose} saveFn={saveFn} testFn={vi.fn()} />,
    );

    await user.type(screen.getByTestId('connection-name'), 'local');
    await user.click(screen.getByTestId('connection-save'));

    expect(saveFn).toHaveBeenCalledWith(
      expect.objectContaining({
        connect: true,
        form: expect.objectContaining({ name: 'local', mode: 'CASSANDRA' }),
      }),
    );
    expect(onClose).toHaveBeenCalled();
  });

  it('keeps the dialog open and shows the server problem when saving fails', async () => {
    const user = userEvent.setup();
    const onClose = vi.fn();
    const saveFn = vi.fn(async () => {
      throw new Error("A connection named 'local' already exists");
    });

    renderWithProviders(
      <ConnectionDialog open onClose={onClose} saveFn={saveFn} testFn={vi.fn()} />,
    );

    await user.type(screen.getByTestId('connection-name'), 'local');
    await user.click(screen.getByTestId('connection-save'));

    expect(await screen.findByTestId('connection-error')).toHaveTextContent(/already exists/i);
    expect(onClose).not.toHaveBeenCalled();
  });

  it('tests unsaved form input and renders the cluster it reached', async () => {
    const user = userEvent.setup();
    const testFn = vi.fn(async () => ({
      success: true,
      elapsedMillis: 412,
      clusterName: 'Test Cluster',
      releaseVersion: '5.0.2',
    }));

    renderWithProviders(
      <ConnectionDialog open onClose={vi.fn()} saveFn={vi.fn()} testFn={testFn as never} />,
    );

    await user.type(screen.getByTestId('connection-name'), 'local');
    await user.click(screen.getByTestId('connection-test'));

    const result = await screen.findByTestId('connection-test-result');
    expect(result).toHaveTextContent(/Test Cluster/);
    expect(result).toHaveTextContent(/5\.0\.2/);
    expect(vi.mocked(testFn).mock.calls.at(0)?.[0]).toHaveProperty('connection');
  });

  /** A failed probe is a 200 with success:false - the diagnostic must survive to the user. */
  it('renders a failed probe as an error rather than swallowing it', async () => {
    const user = userEvent.setup();
    const testFn = vi.fn(async () => ({
      success: false,
      elapsedMillis: 20,
      problem: {
        type: 'https://cassyx.dev/problems/connection-failed',
        title: 'Could not reach cluster',
        status: 502,
        detail: 'AllNodesFailedException: Could not reach any contact point (127.0.0.1:9042).',
      },
    })) as never;

    renderWithProviders(
      <ConnectionDialog open onClose={vi.fn()} saveFn={vi.fn()} testFn={testFn} />,
    );

    await user.type(screen.getByTestId('connection-name'), 'local');
    await user.click(screen.getByTestId('connection-test'));

    expect(await screen.findByTestId('connection-test-result')).toHaveTextContent(
      /Could not reach any contact point/,
    );
  });

  /**
   * SECURITY: the prior art rendered the Astra token in plaintext. A backend that echoed a request
   * back in an error must not be able to reintroduce that through this path.
   */
  it('redacts anything token-shaped in a server error before displaying it', async () => {
    const user = userEvent.setup();
    const saveFn = vi.fn(async () => {
      throw new Error('rejected request with AstraCS:abcdef:0123456789');
    });

    renderWithProviders(
      <ConnectionDialog open onClose={vi.fn()} saveFn={saveFn} testFn={vi.fn()} />,
    );

    await user.type(screen.getByTestId('connection-name'), 'local');
    await user.click(screen.getByTestId('connection-save'));

    const alert = await screen.findByTestId('connection-error');
    expect(alert).not.toHaveTextContent('AstraCS:abcdef:0123456789');
    expect(alert).toHaveTextContent('AstraCS:[REDACTED]');
  });

  it('does not call the API at all while the form is invalid', async () => {
    const user = userEvent.setup();
    const saveFn = vi.fn();
    const testFn = vi.fn();

    renderWithProviders(
      <ConnectionDialog open onClose={vi.fn()} saveFn={saveFn as never} testFn={testFn as never} />,
    );

    await user.click(screen.getByTestId('connection-test'));
    await user.click(screen.getByTestId('connection-save'));

    expect(saveFn).not.toHaveBeenCalled();
    expect(testFn).not.toHaveBeenCalled();
  });
});
