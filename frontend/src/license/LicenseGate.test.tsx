import { describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '../test/render';
import { LicenseGate } from './LicenseGate';
import { BypassBanner } from './BypassBanner';
import { useLicense } from './licenseModel';
import type { LicenseStatus } from '../api/types';

function Protected() {
  const license = useLicense();
  return (
    <div>
      <span data-testid="protected">protected content</span>
      {license.showBypassBanner && <BypassBanner />}
    </div>
  );
}

const licensed: LicenseStatus = {
  licensed: true,
  enforce: true,
  bypass: false,
  edition: 'standard',
  name: 'Acme Corp',
};

const bypassed: LicenseStatus = {
  licensed: false,
  enforce: false,
  bypass: true,
  edition: 'unlicensed-bypass',
};

const unlicensed: LicenseStatus = {
  licensed: false,
  enforce: true,
  bypass: false,
  edition: 'none',
};

describe('LicenseGate', () => {
  it('renders the app for a valid license, with no bypass banner', () => {
    renderWithProviders(
      <LicenseGate statusOverride={licensed}>
        <Protected />
      </LicenseGate>,
    );

    expect(screen.getByTestId('protected')).toBeInTheDocument();
    expect(screen.queryByTestId('license-bypass-banner')).not.toBeInTheDocument();
  });

  it('blocks the app and shows the activation screen when unlicensed', () => {
    renderWithProviders(
      <LicenseGate statusOverride={unlicensed}>
        <Protected />
      </LicenseGate>,
    );

    expect(screen.queryByTestId('protected')).not.toBeInTheDocument();
    expect(screen.getByTestId('activation-screen')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /purchase a license/i })).toBeInTheDocument();
  });

  it('explains why an invalid key was rejected', () => {
    renderWithProviders(
      <LicenseGate statusOverride={{ ...unlicensed, message: 'Signature mismatch.' }}>
        <Protected />
      </LicenseGate>,
    );
    expect(screen.getByText('Signature mismatch.')).toBeInTheDocument();
  });

  it('unlocks in bypass mode AND shows the persistent banner', () => {
    renderWithProviders(
      <LicenseGate statusOverride={bypassed}>
        <Protected />
      </LicenseGate>,
    );

    expect(screen.getByTestId('protected')).toBeInTheDocument();

    const banner = screen.getByTestId('license-bypass-banner');
    expect(banner).toBeInTheDocument();
    expect(banner).toHaveTextContent(/enforcement is disabled/i);
    // The banner must be permanent — no dismiss/close affordance of any kind.
    expect(banner.querySelector('button')).toBeNull();
  });

  it('rejects a malformed key before it ever reaches the API', async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <LicenseGate statusOverride={unlicensed}>
        <Protected />
      </LicenseGate>,
    );

    await user.type(screen.getByTestId('license-key-input'), 'obviously not a license key');
    expect(screen.getByRole('button', { name: /activate/i })).toBeDisabled();
    expect(screen.getByText(/does not look like a cassyx key/i)).toBeInTheDocument();
  });

  it('shows a splash while the status is loading and never mounts the shell', () => {
    const fetchSpy = vi
      .spyOn(globalThis, 'fetch')
      .mockImplementation(() => new Promise(() => {}) as Promise<Response>);

    renderWithProviders(
      <LicenseGate>
        <Protected />
      </LicenseGate>,
    );

    expect(screen.getByTestId('license-splash')).toBeInTheDocument();
    expect(screen.queryByTestId('protected')).not.toBeInTheDocument();
    fetchSpy.mockRestore();
  });

  it('offers a retry when the license endpoint is unreachable', async () => {
    const fetchSpy = vi
      .spyOn(globalThis, 'fetch')
      .mockRejectedValue(new TypeError('Failed to fetch'));

    renderWithProviders(
      <LicenseGate>
        <Protected />
      </LicenseGate>,
    );

    await waitFor(() => expect(screen.getByTestId('license-unavailable')).toBeInTheDocument(), {
      timeout: 5000,
    });
    expect(screen.queryByTestId('protected')).not.toBeInTheDocument();
    fetchSpy.mockRestore();
  });
});
