import { describe, expect, it, vi } from 'vitest';
import { emptyConnectionForm, type ConnectionFormState } from './connectionModel';
import { saveConnection, type SaveConnectionTransport } from './saveConnection';

function transport(): SaveConnectionTransport & { order: string[] } {
  const order: string[] = [];
  return {
    order,
    create: vi.fn(async (request) => {
      order.push('create');
      return { id: 'c1', name: request.name } as never;
    }),
    update: vi.fn(async (connectionId) => {
      order.push('update');
      return { id: connectionId } as never;
    }),
    uploadBundle: vi.fn(async () => {
      order.push('uploadBundle');
      return { id: 'c1' } as never;
    }),
    downloadBundle: vi.fn(async () => {
      order.push('downloadBundle');
      return {};
    }),
    connect: vi.fn(async (connectionId) => {
      order.push('connect');
      return { connectionId, connected: true } as never;
    }),
  };
}

function astraForm(overrides: Partial<ConnectionFormState['astra']> = {}): ConnectionFormState {
  const form = emptyConnectionForm();
  return {
    ...form,
    name: 'prod-eu',
    mode: 'ASTRA',
    astra: {
      ...form.astra,
      astraToken: 'AstraCS:abcdef:0123456789',
      databaseId: 'f9a1b3c4-1111-2222-3333-444455556666',
      ...overrides,
    },
  };
}

describe('saveConnection', () => {
  it('creates then connects for a plain Cassandra connection', async () => {
    const t = transport();
    const form = { ...emptyConnectionForm(), name: 'local-dev' };

    const result = await saveConnection({ form, transport: t });

    expect(t.order).toEqual(['create', 'connect']);
    expect(result.connection.id).toBe('c1');
    expect(result.session?.connected).toBe(true);
    expect(result.bundleStored).toBe(false);
  });

  it('updates instead of creating when a connection id is supplied', async () => {
    const t = transport();

    await saveConnection({ form: emptyConnectionForm(), connectionId: 'existing', transport: t });

    expect(t.order).toEqual(['update', 'connect']);
    expect(t.create).not.toHaveBeenCalled();
  });

  /**
   * The ordering that matters: the bundle endpoints are keyed by connection id, so uploading before
   * the connection exists has nowhere to put the file.
   */
  it('creates the connection BEFORE uploading the bundle', async () => {
    const t = transport();
    const file = new File(['zip'], 'secure-connect-prod.zip');

    const result = await saveConnection({
      form: astraForm({ acquisitionMode: 'UPLOAD', bundleFileName: 'secure-connect-prod.zip' }),
      bundleFile: file,
      transport: t,
    });

    expect(t.order).toEqual(['create', 'uploadBundle', 'connect']);
    expect(t.uploadBundle).toHaveBeenCalledWith('c1', file);
    expect(result.bundleStored).toBe(true);
  });

  it('asks the server to download the bundle in AUTO_DOWNLOAD mode', async () => {
    const t = transport();

    await saveConnection({ form: astraForm({ region: 'us-east1' }), transport: t });

    expect(t.order).toEqual(['create', 'downloadBundle', 'connect']);
    expect(t.downloadBundle).toHaveBeenCalledWith('f9a1b3c4-1111-2222-3333-444455556666', {
      connectionId: 'c1',
      astraToken: 'AstraCS:abcdef:0123456789',
      region: 'us-east1',
      scbType: 'default',
      domain: undefined,
      force: false,
    });
  });

  it('sends a domain only for a custom bundle type', async () => {
    const t = transport();

    await saveConnection({
      form: astraForm({ scbType: 'custom', customDomain: 'cassandra.example.com' }),
      transport: t,
    });

    const downloadArgs = vi.mocked(t.downloadBundle).mock.calls.at(0);
    expect(downloadArgs?.[1]).toMatchObject({
      scbType: 'custom',
      domain: 'cassandra.example.com',
    });
  });

  /** PATH mode resolves server-side from the connection itself; there is nothing to transfer. */
  it('transfers nothing in PATH mode', async () => {
    const t = transport();

    const result = await saveConnection({
      form: astraForm({ acquisitionMode: 'PATH', bundlePath: '/etc/cassyx/scb/prod.zip' }),
      transport: t,
    });

    expect(t.order).toEqual(['create', 'connect']);
    expect(result.bundleStored).toBe(false);
  });

  it('can save without connecting', async () => {
    const t = transport();

    const result = await saveConnection({
      form: emptyConnectionForm(),
      connect: false,
      transport: t,
    });

    expect(t.order).toEqual(['create']);
    expect(result.session).toBeUndefined();
  });

  it('does not connect when the bundle upload fails', async () => {
    const t = transport();
    t.uploadBundle = vi.fn(async () => {
      throw new Error('That file is not a secure connect bundle');
    });

    await expect(
      saveConnection({
        form: astraForm({ acquisitionMode: 'UPLOAD' }),
        bundleFile: new File(['nope'], 'wrong.zip'),
        transport: t,
      }),
    ).rejects.toThrow('not a secure connect bundle');
    expect(t.connect).not.toHaveBeenCalled();
  });
});
