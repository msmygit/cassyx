import { describe, expect, it, vi } from 'vitest';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '../../test/render';
import { LoadJobForm } from './LoadJobForm';
import type { DsbulkApi } from './dsbulkApi';

function stubApi(overrides: Partial<DsbulkApi> = {}): DsbulkApi {
  return {
    deriveDefaults: vi.fn(),
    previewCommand: vi.fn(),
    createLoadJob: vi.fn(),
    createCountJob: vi.fn(),
    uploadSourceFile: vi.fn(async (file: File) => ({
      uploadId: 'up_01J8Z1',
      fileName: file.name,
      sizeBytes: 8,
      uploadedAt: '2026-08-17T11:40:00Z',
    })),
    ...overrides,
  } as DsbulkApi;
}

// The Advanced accordion renders the whole DSBulk settings surface; under coverage instrumentation
// a single interaction test can exceed the 5s default.
vi.setConfig({ testTimeout: 20_000 });

describe('LoadJobForm', () => {
  it('refuses to submit without a target or a source, and says why', async () => {
    const onSubmit = vi.fn();
    renderWithProviders(<LoadJobForm connectionId="c1" onSubmit={onSubmit} api={stubApi()} />);

    fireEvent.click(screen.getByTestId('load-submit'));

    expect(onSubmit).not.toHaveBeenCalled();
    expect(screen.getByText('Keyspace is required.')).toBeInTheDocument();
    expect(screen.getByText('Table is required.')).toBeInTheDocument();
    expect(
      screen.getByText('Choose an uploaded file, a server path or an S3 URI.'),
    ).toBeInTheDocument();
  });

  it('stages an uploaded file and submits it as the source', async () => {
    const user = userEvent.setup();
    const onSubmit = vi.fn();
    const api = stubApi();
    renderWithProviders(
      <LoadJobForm connectionId="c1" keyspace="demo" table="users" onSubmit={onSubmit} api={api} />,
    );

    await user.upload(
      screen.getByTestId('load-file'),
      new File(['a,b\n1,2\n'], 'users.csv', { type: 'text/csv' }),
    );

    await waitFor(() => expect(screen.getByTestId('upload-name')).toHaveTextContent('users.csv'));
    expect(api.uploadSourceFile).toHaveBeenCalledWith(expect.any(File), 'CSV');

    fireEvent.click(screen.getByTestId('load-submit'));
    expect(onSubmit).toHaveBeenCalledWith(
      expect.objectContaining({
        keyspace: 'demo',
        table: 'users',
        dryRun: false,
        source: { uploadId: 'up_01J8Z1', format: 'CSV', compression: 'AUTO' },
      }),
    );
  });

  it('surfaces an upload failure instead of silently keeping the old handle', async () => {
    const user = userEvent.setup();
    const api = stubApi({
      uploadSourceFile: vi.fn(async () => {
        throw new Error('Payload too large');
      }),
    });
    renderWithProviders(<LoadJobForm connectionId="c1" onSubmit={vi.fn()} api={api} />);

    await user.upload(screen.getByTestId('load-file'), new File(['x'], 'huge.csv'));
    expect(await screen.findByText('Payload too large')).toBeInTheDocument();
  });

  it('accepts a server-side path as the source', async () => {
    const onSubmit = vi.fn();
    renderWithProviders(
      <LoadJobForm
        connectionId="c1"
        keyspace="demo"
        table="users"
        onSubmit={onSubmit}
        api={stubApi()}
      />,
    );

    fireEvent.click(screen.getByRole('radio', { name: 'Server path' }));
    fireEvent.change(screen.getByTestId('load-path'), {
      target: { value: '/data/imports/users.csv' },
    });
    fireEvent.click(screen.getByTestId('load-submit'));

    expect(onSubmit).toHaveBeenCalledWith(
      expect.objectContaining({
        source: { path: '/data/imports/users.csv', format: 'CSV', compression: 'AUTO' },
      }),
    );
  });

  it('accepts an S3 URI as the source', async () => {
    const onSubmit = vi.fn();
    renderWithProviders(
      <LoadJobForm
        connectionId="c1"
        keyspace="demo"
        table="users"
        onSubmit={onSubmit}
        api={stubApi()}
      />,
    );

    fireEvent.click(screen.getByRole('radio', { name: 'S3 URI' }));
    fireEvent.change(screen.getByTestId('load-s3'), { target: { value: 's3://bucket/users.csv' } });
    fireEvent.click(screen.getByTestId('load-submit'));

    expect(onSubmit).toHaveBeenCalledWith(
      expect.objectContaining({
        source: { s3Uri: 's3://bucket/users.csv', format: 'CSV', compression: 'AUTO' },
      }),
    );
  });

  it('builds the DSBulk mapping string from the field/column rows', async () => {
    const onSubmit = vi.fn();
    renderWithProviders(
      <LoadJobForm
        connectionId="c1"
        keyspace="demo"
        table="users"
        onSubmit={onSubmit}
        api={stubApi()}
      />,
    );

    fireEvent.change(screen.getByTestId('mapping-field-0'), { target: { value: 'mail' } });
    fireEvent.change(screen.getByTestId('mapping-column-0'), { target: { value: 'email' } });
    fireEvent.click(screen.getByRole('button', { name: /add mapping/i }));
    fireEvent.change(screen.getByTestId('mapping-field-1'), { target: { value: 'id' } });
    fireEvent.change(screen.getByTestId('mapping-column-1'), { target: { value: 'user_id' } });

    expect(screen.getByTestId('mapping-string')).toHaveValue('mail=email, id=user_id');

    fireEvent.click(screen.getByRole('button', { name: 'Remove mapping row 2' }));
    expect(screen.getByTestId('mapping-string')).toHaveValue('mail=email');

    fireEvent.click(screen.getByRole('radio', { name: 'Server path' }));
    fireEvent.change(screen.getByTestId('load-path'), { target: { value: '/data/users.csv' } });
    fireEvent.click(screen.getByTestId('load-submit'));

    expect(onSubmit).toHaveBeenCalledWith(expect.objectContaining({ mapping: 'mail=email' }));
  });

  it('lets the mapping string be edited directly and reflects it back into the rows', () => {
    renderWithProviders(<LoadJobForm connectionId="c1" onSubmit={vi.fn()} api={stubApi()} />);

    fireEvent.change(screen.getByTestId('mapping-string'), {
      target: { value: '0=user_id, 1=email' },
    });
    expect(screen.getByTestId('mapping-field-0')).toHaveValue('0');
    expect(screen.getByTestId('mapping-column-1')).toHaveValue('email');
  });

  it('submits a dry run when asked', async () => {
    const onSubmit = vi.fn();
    renderWithProviders(
      <LoadJobForm
        connectionId="c1"
        keyspace="demo"
        table="users"
        onSubmit={onSubmit}
        api={stubApi()}
      />,
    );

    fireEvent.click(screen.getByRole('radio', { name: 'Server path' }));
    fireEvent.change(screen.getByTestId('load-path'), { target: { value: '/data/users.csv' } });
    fireEvent.click(screen.getByRole('checkbox', { name: 'Dry run' }));
    fireEvent.click(screen.getByTestId('load-submit'));

    expect(onSubmit).toHaveBeenCalledWith(expect.objectContaining({ dryRun: true }));
  });

  it('embeds the shared settings form and forwards its overrides into the request', async () => {
    const onSubmit = vi.fn();
    const onSettingsChange = vi.fn();
    renderWithProviders(
      <LoadJobForm
        connectionId="c1"
        keyspace="demo"
        table="users"
        onSubmit={onSubmit}
        onSettingsChange={onSettingsChange}
        api={stubApi()}
      />,
    );

    expect(screen.getByTestId('dsbulk-settings-form')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('tab', { name: 'Advanced' }));
    fireEvent.click(screen.getByRole('button', { name: /Batching/ }));
    fireEvent.change(screen.getByTestId('setting-batch.maxBatchStatements'), {
      target: { value: '64' },
    });

    expect(onSettingsChange).toHaveBeenLastCalledWith({ 'batch.maxBatchStatements': '64' });

    fireEvent.click(screen.getByRole('radio', { name: 'Server path' }));
    fireEvent.change(screen.getByTestId('load-path'), { target: { value: '/data/users.csv' } });
    fireEvent.click(screen.getByTestId('load-submit'));

    expect(onSubmit).toHaveBeenCalledWith(
      expect.objectContaining({ dsbulkSettings: { batch: { maxBatchStatements: 64 } } }),
    );
  });

  it('hides the count-only statistics group from the load flow', async () => {
    renderWithProviders(<LoadJobForm connectionId="c1" onSubmit={vi.fn()} api={stubApi()} />);

    fireEvent.click(screen.getByRole('tab', { name: 'Advanced' }));
    expect(screen.getByTestId('group-connector')).toBeInTheDocument();
    expect(screen.queryByTestId('group-stats')).not.toBeInTheDocument();
  });

  it('picks up a pre-selected target and a chosen format', async () => {
    const user = userEvent.setup();
    const api = stubApi();
    renderWithProviders(
      <LoadJobForm connectionId="c1" keyspace="demo" table="users" onSubmit={vi.fn()} api={api} />,
    );

    expect(screen.getByTestId('load-keyspace')).toHaveValue('demo');
    expect(screen.getByTestId('load-table')).toHaveValue('users');

    await user.click(screen.getByRole('combobox', { name: 'Format' }));
    await user.click(screen.getByRole('option', { name: 'PARQUET' }));
    await user.upload(screen.getByTestId('load-file'), new File(['x'], 'users.parquet'));

    await waitFor(() =>
      expect(api.uploadSourceFile).toHaveBeenCalledWith(expect.any(File), 'PARQUET'),
    );
  });

  it('names the job when a name is given', async () => {
    const onSubmit = vi.fn();
    renderWithProviders(
      <LoadJobForm
        connectionId="c1"
        keyspace="demo"
        table="users"
        onSubmit={onSubmit}
        api={stubApi()}
      />,
    );

    fireEvent.change(screen.getByTestId('load-name'), {
      target: { value: 'Nightly users import' },
    });
    fireEvent.click(screen.getByRole('radio', { name: 'Server path' }));
    fireEvent.change(screen.getByTestId('load-path'), { target: { value: '/data/users.csv' } });
    fireEvent.click(screen.getByTestId('load-submit'));

    expect(onSubmit).toHaveBeenCalledWith(
      expect.objectContaining({ name: 'Nightly users import' }),
    );
  });
});
