/**
 * The licence gate as a real customer meets it (plan §9.1): a gated `/api/**` call comes back
 * `402 application/problem+json`, and the app has to land on the activation screen for the state
 * the backend reports - not on a generic error, and not by throwing away the shell.
 */
import { useState } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useQuery } from '@tanstack/react-query';
import { renderWithProviders } from '../test/render';
import { listConnections } from '../api/endpoints';
import { queryKeys } from '../api/queryClient';
import { resetLicenseRequiredListeners } from '../api/licenseSignal';
import type { LicenseStatus } from '../api/types';
import { LicenseGate } from './LicenseGate';
import { BypassBanner } from './BypassBanner';
import { useLicense } from './licenseModel';

const VALID_KEY = 'CSXTESTKEYPAYLOAD.SIGNATURE';

const licensed: LicenseStatus = {
  licensed: true,
  enforce: true,
  bypass: false,
  edition: 'standard',
  state: 'VALID',
  name: 'Acme Corp',
};

function locked(state: NonNullable<LicenseStatus['state']>): LicenseStatus {
  return {
    licensed: false,
    enforce: true,
    bypass: false,
    edition: 'none',
    state,
    ...(state === 'EXPIRED'
      ? { name: 'Acme Corp', email: 'ops@acme.example', expires: '2026-08-01' }
      : {}),
    ...(state === 'UPGRADE_REQUIRED' ? { scope: 1 } : {}),
    ...(state === 'INVALID_SIGNATURE' ? { message: 'Signature verification failed.' } : {}),
  };
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  });
}

function licenseRequired(state: string): Response {
  return new Response(
    JSON.stringify({
      type: 'https://cassyx.dev/problems/license-required',
      title: 'License required',
      status: 402,
      detail: 'This cassyx instance is not licensed.',
      state,
      invitesPurchase: state !== 'INVALID_SIGNATURE',
      unlockHint: 'Activate a key, or start the server with CASSYX_LICENSE_ENFORCE=false.',
    }),
    { status: 402, headers: { 'content-type': 'application/problem+json' } },
  );
}

interface Stack {
  /** What `GET /api/license` currently answers. Flipped by the fake gate, like the real one. */
  status: LicenseStatus;
  fetchMock: ReturnType<typeof vi.fn>;
  licenseCalls: () => number;
  connectionCalls: () => number;
}

/**
 * The scenario a real customer hits: the shell is already open on a licence that has since
 * lapsed (or was never there, on a cached status), and the next gated call is refused.
 *
 * The fake gate and the fake status endpoint agree from the moment of the first refusal, which is
 * the invariant plan §9.1 guarantees by having both read one `LicenseGate` bean.
 */
function stubBackend(gateState: NonNullable<LicenseStatus['state']>): Stack {
  const stack: Partial<Stack> = { status: licensed };
  let activated = false;
  let licenseCalls = 0;
  let connectionCalls = 0;

  const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url.includes('/api/license/activate')) {
      activated = true;
      stack.status = licensed;
      return json(licensed);
    }
    if (url.includes('/api/license')) {
      licenseCalls += 1;
      return json(stack.status);
    }
    connectionCalls += 1;
    if (activated) return json([{ id: 'c1', name: 'local' }]);
    stack.status = locked(gateState);
    return licenseRequired(gateState);
  });

  vi.stubGlobal('fetch', fetchMock);
  return Object.assign(stack, {
    fetchMock,
    licenseCalls: () => licenseCalls,
    connectionCalls: () => connectionCalls,
  }) as Stack;
}

/** A shell with a gated query and some unsaved work in it. */
function Workspace() {
  const license = useLicense();
  const [draft, setDraft] = useState('');
  const [loadRequested, setLoadRequested] = useState(false);
  const connections = useQuery({
    queryKey: queryKeys.connections,
    queryFn: () => listConnections(),
    enabled: loadRequested,
    retry: false,
  });

  return (
    <div>
      <span data-testid="protected">protected content</span>
      <span data-testid="connections-status">{connections.status}</span>
      <label>
        Draft
        <input
          data-testid="draft"
          value={draft}
          onChange={(event) => setDraft(event.target.value)}
        />
      </label>
      <button type="button" onClick={() => setLoadRequested(true)}>
        Load connections
      </button>
      {license.showBypassBanner && <BypassBanner />}
    </div>
  );
}

function renderApp() {
  return renderWithProviders(
    <LicenseGate>
      <Workspace />
    </LicenseGate>,
  );
}

beforeEach(() => {
  resetLicenseRequiredListeners();
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

describe('402 from a gated call (plan §9.1)', () => {
  it.each([
    ['ABSENT', /welcome to cassyx/i],
    ['EXPIRED', /welcome back, acme corp/i],
    ['UPGRADE_REQUIRED', /still works fine on the version it was purchased for/i],
    ['INVALID_SIGNATURE', /doesn't check out/i],
  ] as const)('routes a %s refusal to its own screen', async (state, marker) => {
    const user = userEvent.setup();
    stubBackend(state);
    renderApp();

    await screen.findByTestId('protected');
    await user.click(screen.getByRole('button', { name: /load connections/i }));

    expect(await screen.findByTestId('activation-screen')).toBeInTheDocument();
    expect(screen.getByText(marker)).toBeInTheDocument();
  });

  it('parks the shell instead of unmounting it, so unsaved work survives the lock', async () => {
    const user = userEvent.setup();
    stubBackend('EXPIRED');
    renderApp();

    await screen.findByTestId('protected');
    await user.type(screen.getByTestId('draft'), 'SELECT * FROM ks.orders');
    await user.click(screen.getByRole('button', { name: /load connections/i }));

    await screen.findByTestId('activation-screen');
    // Still mounted (the query the user typed is intact) but not reachable behind the gate.
    expect(screen.getByTestId('draft')).toHaveValue('SELECT * FROM ks.orders');
    expect(screen.getByTestId('protected')).not.toBeVisible();
  });

  it('recovers in place after activation, and retries what the gate refused', async () => {
    const user = userEvent.setup();
    const backend = stubBackend('ABSENT');
    renderApp();

    await screen.findByTestId('protected');
    await user.type(screen.getByTestId('draft'), 'SELECT now() FROM system.local');
    await user.click(screen.getByRole('button', { name: /load connections/i }));
    await screen.findByTestId('activation-screen');
    expect(backend.connectionCalls()).toBe(1);

    await user.click(screen.getByText(/i already have a license key/i));
    await user.type(screen.getByTestId('license-key-input'), VALID_KEY);
    await user.click(screen.getByRole('button', { name: /^activate$/i }));

    await waitFor(() => expect(screen.getByTestId('protected')).toBeVisible());
    // No reload: the editor content is the same React state it always was.
    expect(screen.getByTestId('draft')).toHaveValue('SELECT now() FROM system.local');
    // The query that was refused is retried without the user asking again.
    await waitFor(() =>
      expect(screen.getByTestId('connections-status')).toHaveTextContent('success'),
    );
    expect(backend.connectionCalls()).toBe(2);
  });

  it('cannot be driven into a refetch loop by a burst of refusals', async () => {
    const user = userEvent.setup();
    const backend = stubBackend('ABSENT');
    const { queryClient } = renderApp();

    await screen.findByTestId('protected');
    await user.click(screen.getByRole('button', { name: /load connections/i }));
    await screen.findByTestId('activation-screen');

    const afterFirst = backend.licenseCalls();
    for (let i = 0; i < 5; i += 1) {
      await queryClient.refetchQueries({ queryKey: queryKeys.connections });
    }

    // Every one of those 402s asked for a licence re-check; the throttle collapsed them.
    expect(backend.connectionCalls()).toBeGreaterThan(1);
    expect(backend.licenseCalls()).toBe(afterFirst);
  });
});

describe('bypass is not regressed by the 402 path (plan §9.2)', () => {
  it('stays unlocked with a permanent banner even when the signals disagree', async () => {
    const user = userEvent.setup();
    // `enforce: false` alone. A mismatch must fail towards SHOWING the banner, never towards
    // hiding it - and never towards locking a deliberately bypassed instance.
    const bypassed: LicenseStatus = {
      licensed: false,
      enforce: false,
      bypass: false,
      edition: 'standard',
      state: 'VALID',
    };
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      if (String(input).includes('/api/license')) return json(bypassed);
      return licenseRequired('ABSENT');
    });
    vi.stubGlobal('fetch', fetchMock);

    renderApp();
    await screen.findByTestId('protected');
    await user.click(screen.getByRole('button', { name: /load connections/i }));

    await waitFor(() =>
      expect(screen.getByTestId('connections-status')).toHaveTextContent('error'),
    );
    expect(screen.queryByTestId('activation-screen')).not.toBeInTheDocument();
    expect(screen.getByTestId('protected')).toBeVisible();
    expect(screen.getByTestId('license-bypass-banner')).toBeInTheDocument();
  });
});
