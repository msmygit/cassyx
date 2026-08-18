import { useState } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '../test/render';
import { AstraConnectionForm } from './AstraConnectionForm';
import type { AstraApi } from './astraApi';
import { emptyConnectionForm, type AstraFormState } from './connectionModel';
import type { AstraBundleDatacenter, AstraDatabase } from '../api/types';

const TOKEN = 'AstraCS:abcDEF123:9f8e7d6c5b4a';

const DATABASES: AstraDatabase[] = [
  { id: 'db-active', name: 'prod-vectors', status: 'ACTIVE', regions: ['us-east1'] },
  { id: 'db-sleeping', name: 'old-db', status: 'HIBERNATED', regions: ['eu-west-1'] },
];

const BUNDLES: AstraBundleDatacenter[] = [
  {
    region: 'us-east1',
    downloadURL: 'https://astra.example/scb-us-east1.zip',
    customDomainBundles: [
      { domain: 'cql.prod.example.com', downloadURL: 'https://astra.example/c.zip' },
    ],
  },
  {
    region: 'eu-central-1',
    downloadURL: 'https://astra.example/scb-eu.zip',
    customDomainBundles: [],
  },
];

function mockApi(overrides: Partial<AstraApi> = {}): AstraApi {
  return {
    listDatabases: vi.fn(async () => DATABASES),
    listBundles: vi.fn(async () => BUNDLES),
    redownload: vi.fn(async () => ({ refreshedAt: '2026-08-17T00:00:00Z' })),
    ...overrides,
  };
}

function Harness({
  api,
  initial,
  connectionId,
}: {
  api: AstraApi;
  initial?: Partial<AstraFormState>;
  /** Absent while creating — the bundle endpoints are keyed by connection id. */
  connectionId?: string;
}) {
  const [value, setValue] = useState<AstraFormState>({
    ...emptyConnectionForm().astra,
    ...initial,
  });
  return (
    <AstraConnectionForm
      value={value}
      onChange={setValue}
      api={api}
      {...(connectionId ? { connectionId } : {})}
    />
  );
}

async function selectOption(
  user: ReturnType<typeof userEvent.setup>,
  testId: string,
  name: RegExp,
) {
  const select = screen.getByTestId(testId).closest('.MuiInputBase-root');
  await user.click(within(select as HTMLElement).getByRole('combobox'));
  await user.click(await screen.findByRole('option', { name }));
}

describe('AstraConnectionForm', () => {
  it('defaults to AUTO_DOWNLOAD', () => {
    renderWithProviders(<Harness api={mockApi()} />);
    expect(screen.getByRole('radio', { name: /download automatically/i })).toBeChecked();
  });

  it('masks the Astra token by default and reveals it only on request', async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness api={mockApi()} initial={{ astraToken: TOKEN }} />);

    const input = screen.getByTestId('astra-token');
    expect(input).toHaveAttribute('type', 'password');

    await user.click(screen.getByRole('button', { name: /reveal value/i }));
    expect(input).toHaveAttribute('type', 'text');

    await user.click(screen.getByRole('button', { name: /hide value/i }));
    expect(input).toHaveAttribute('type', 'password');
  });

  it('will not query the DevOps API until the token is well-formed', async () => {
    const api = mockApi();
    renderWithProviders(<Harness api={api} initial={{ astraToken: 'junk' }} />);
    expect(screen.getByTestId('astra-load-databases')).toBeDisabled();
    expect(api.listDatabases).not.toHaveBeenCalled();
  });

  it('walks the full auto-download flow: databases → region → custom domain', async () => {
    const user = userEvent.setup();
    const api = mockApi();
    renderWithProviders(<Harness api={api} initial={{ astraToken: TOKEN }} />);

    await user.click(screen.getByTestId('astra-load-databases'));
    await waitFor(() => expect(api.listDatabases).toHaveBeenCalledWith(TOKEN));

    // Database PICKER, not a UUID text field.
    await selectOption(user, 'astra-database-select', /prod-vectors/);
    await waitFor(() => expect(api.listBundles).toHaveBeenCalledWith('db-active', TOKEN));

    // Region dropdown populated from the real bundle response.
    await selectOption(user, 'astra-region-select', /us-east1/);

    // Bundle type has EXACTLY two options — region is a separate field, not a third type.
    const typeSelect = screen.getByTestId('astra-scb-type-select').closest('.MuiInputBase-root');
    await user.click(within(typeSelect as HTMLElement).getByRole('combobox'));
    const options = screen.getAllByRole('option');
    expect(options).toHaveLength(2);
    expect(options.map((o) => o.textContent)).toEqual(['default', 'custom domain']);
    await user.click(screen.getByRole('option', { name: /custom domain/i }));

    // Domain dropdown, populated from customDomainBundles for the selected region.
    await selectOption(user, 'astra-domain-select', /cql\.prod\.example\.com/);
    expect(screen.getByTestId('astra-domain-select')).toHaveValue('cql.prod.example.com');
  });

  it('disables databases that cannot be connected to', async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness api={mockApi()} initial={{ astraToken: TOKEN }} />);
    await user.click(screen.getByTestId('astra-load-databases'));
    await selectOption(user, 'astra-database-select', /prod-vectors/);

    const select = screen.getByTestId('astra-database-select').closest('.MuiInputBase-root');
    await user.click(within(select as HTMLElement).getByRole('combobox'));
    expect(screen.getByRole('option', { name: /old-db/ })).toHaveAttribute('aria-disabled', 'true');
  });

  it('offers a re-download action for rotated bundles', async () => {
    const user = userEvent.setup();
    const api = mockApi();
    renderWithProviders(<Harness api={api} initial={{ astraToken: TOKEN }} connectionId="c1" />);

    await user.click(screen.getByTestId('astra-load-databases'));
    await selectOption(user, 'astra-database-select', /prod-vectors/);
    await user.click(await screen.findByTestId('astra-redownload'));

    // The refreshed bundle is stored against a connection, so the id travels with the request.
    await waitFor(() =>
      expect(api.redownload).toHaveBeenCalledWith('db-active', TOKEN, {
        connectionId: 'c1',
        region: '',
        scbType: 'default',
        domain: '',
      }),
    );
  });

  /**
   * The bundle is stored encrypted against a connection row, so there is nowhere to put it before
   * the connection exists. Offering the button anyway would produce a 404 the user cannot act on.
   */
  it('cannot re-download before the connection has been saved', async () => {
    const user = userEvent.setup();
    const api = mockApi();
    renderWithProviders(<Harness api={api} initial={{ astraToken: TOKEN }} />);

    await user.click(screen.getByTestId('astra-load-databases'));
    await selectOption(user, 'astra-database-select', /prod-vectors/);

    expect(await screen.findByTestId('astra-redownload')).toBeDisabled();
    expect(screen.getByText(/Save the connection first/i)).toBeInTheDocument();
    expect(api.redownload).not.toHaveBeenCalled();
  });

  it('never leaks the token into an error message', async () => {
    const user = userEvent.setup();
    const api = mockApi({
      listDatabases: vi.fn(async () => {
        // A backend that carelessly echoes the request is exactly how tokens leak.
        throw new Error(`DevOps API rejected Authorization: Bearer ${TOKEN}`);
      }),
    });
    renderWithProviders(<Harness api={api} initial={{ astraToken: TOKEN }} />);

    await user.click(screen.getByTestId('astra-load-databases'));
    const alert = await screen.findByTestId('astra-error');

    expect(alert.textContent).not.toContain('9f8e7d6c5b4a');
    expect(alert.textContent).toContain('[REDACTED]');
    expect(document.body.textContent).not.toContain('9f8e7d6c5b4a');
  });

  it('points at manual upload when the DevOps API is unreachable', async () => {
    const user = userEvent.setup();
    const api = mockApi({
      listDatabases: vi.fn(async () => {
        throw new Error('');
      }),
    });
    renderWithProviders(<Harness api={api} initial={{ astraToken: TOKEN }} />);
    await user.click(screen.getByTestId('astra-load-databases'));
    expect(await screen.findByTestId('astra-error')).toHaveTextContent(
      /upload the bundle instead/i,
    );
  });

  it('UPLOAD mode takes a file, not a path', async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness api={mockApi()} initial={{ astraToken: TOKEN }} />);

    await user.click(screen.getByRole('radio', { name: /upload a bundle file/i }));
    const file = new File(['zip-bytes'], 'secure-connect-prod.zip', { type: 'application/zip' });
    await user.upload(screen.getByTestId('astra-bundle-file'), file);

    expect(screen.getByText(/secure-connect-prod\.zip/)).toBeInTheDocument();
  });

  it('PATH mode says plainly that the path is resolved on the server', async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness api={mockApi()} initial={{ astraToken: TOKEN }} />);

    await user.click(screen.getByRole('radio', { name: /server-side file path/i }));
    const warning = screen.getByTestId('astra-path-warning');
    expect(warning).toHaveTextContent(/on the cassyx server/i);
    expect(warning).toHaveTextContent(/not on your computer/i);
    expect(warning).toHaveTextContent(/CASSYX_SCB_PATH_ROOT/);
    expect(screen.getByTestId('astra-bundle-path')).toBeInTheDocument();
  });
});
