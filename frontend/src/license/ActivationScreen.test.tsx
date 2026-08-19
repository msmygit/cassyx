import { describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '../test/render';
import { ActivationScreen } from './ActivationScreen';
import type { LicenseAccessDetail } from './licenseModel';

function renderScreen(
  detail: LicenseAccessDetail,
  overrides: Partial<Parameters<typeof ActivationScreen>[0]> = {},
) {
  const onActivate = vi.fn().mockResolvedValue(undefined);
  const onPurchase = vi.fn().mockResolvedValue(undefined);
  const onStartTrial = vi.fn().mockResolvedValue(undefined);
  const result = renderWithProviders(
    <ActivationScreen
      detail={detail}
      onActivate={onActivate}
      onPurchase={onPurchase}
      onStartTrial={onStartTrial}
      {...overrides}
    />,
  );
  return { ...result, onActivate, onPurchase, onStartTrial };
}

describe('ActivationScreen — per-state rendering (plan §9.4/§9.5)', () => {
  it('ABSENT: offers a trial signup and a way to enter an existing key', async () => {
    const user = userEvent.setup();
    renderScreen({ state: 'ABSENT' });

    expect(screen.getByText(/welcome to cassyx/i)).toBeInTheDocument();
    expect(screen.getByTestId('trial-email-input')).toBeInTheDocument();
    expect(screen.queryByTestId('license-key-input')).not.toBeInTheDocument();

    await user.click(screen.getByText(/i already have a license key/i));
    expect(screen.getByTestId('license-key-input')).toBeInTheDocument();
  });

  it('EXPIRED: greets the retained buyer by name and explains the lapse, not an error screen', () => {
    renderScreen({
      state: 'EXPIRED',
      name: 'Acme Corp',
      email: 'ops@acme.example',
      expires: '2026-08-01',
    });

    expect(screen.getByText(/welcome back, acme corp/i)).toBeInTheDocument();
    expect(screen.getByText(/lapsed on 2026-08-01/i)).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('UPGRADE_REQUIRED: reassures the existing key still works and offers the upgrade', () => {
    renderScreen({ state: 'UPGRADE_REQUIRED', scope: 1 });

    expect(
      screen.getByText(/still works fine on the version it was purchased for/i),
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /purchase the upgrade/i })).toBeInTheDocument();
  });

  it('MALFORMED (operator issue): shows the operator-facing message, no key form', () => {
    renderScreen({
      state: 'MALFORMED',
      operatorIssue: true,
      message: 'cassyx.license.public-key is not configured, so no licence can be verified.',
    });

    expect(screen.getByText(/not configured for licensing/i)).toBeInTheDocument();
    expect(screen.getByText(/public-key is not configured/i)).toBeInTheDocument();
    expect(screen.queryByTestId('license-key-input')).not.toBeInTheDocument();
  });

  it('MALFORMED (bad paste): lets the buyer retry entering a key', () => {
    renderScreen({ state: 'MALFORMED', operatorIssue: false, message: 'Malformed license key' });

    expect(screen.getByText(/that key could not be read/i)).toBeInTheDocument();
    expect(screen.getByTestId('license-key-input')).toBeInTheDocument();
  });

  it('INVALID_SIGNATURE: neutral, non-accusatory copy, with a retry path', () => {
    renderScreen({ state: 'INVALID_SIGNATURE', message: 'Signature verification failed.' });

    expect(screen.getByText(/doesn't check out/i)).toBeInTheDocument();
    expect(screen.getByTestId('license-key-input')).toBeInTheDocument();
    expect(screen.queryByText(/tamper|pirat/i)).not.toBeInTheDocument();
  });

  it('UNKNOWN (legacy backend fallback): behaves like the original generic activation screen', () => {
    renderScreen({ state: 'UNKNOWN', message: 'Signature mismatch.' });

    expect(screen.getByText(/activate cassyx/i)).toBeInTheDocument();
    expect(screen.getByText('Signature mismatch.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /purchase a license/i })).toBeInTheDocument();
  });
});
