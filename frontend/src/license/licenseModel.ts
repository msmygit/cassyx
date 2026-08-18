/**
 * Pure license logic (plan §9.1 / §9.2). No React, no network — so it can be tested exhaustively.
 *
 * The critical rule encoded here: when the backend reports `edition: "unlicensed-bypass"` (i.e.
 * `CASSYX_LICENSE_ENFORCE=false`), the app is fully unlocked **and** a persistent banner must be
 * visible. A bypassed instance must never be mistakable for a paid one.
 */
import { createContext, useContext } from 'react';
import type { LicenseStatus } from '../api/types';

export interface LicenseAccess {
  /** May the user reach the application shell? */
  unlocked: boolean;
  /** Enforcement is switched off — everything is unlocked without a key. */
  bypass: boolean;
  /** Render the persistent bypass banner. Always true when `bypass` is true. */
  showBypassBanner: boolean;
  /** A key exists but is not usable — show the reason on the activation screen. */
  invalid: boolean;
  reason: string | null;
}

const BYPASS_EDITION = 'unlicensed-bypass';

export function deriveLicenseAccess(status: LicenseStatus | null | undefined): LicenseAccess {
  if (!status) {
    return {
      unlocked: false,
      bypass: false,
      showBypassBanner: false,
      invalid: false,
      reason: null,
    };
  }

  // Belt and braces: treat ANY of the three signals as bypass. `bypass: true`, `enforce: false`
  // and the sentinel edition are supposed to travel together, but a mismatch must fail safe
  // towards SHOWING the banner — never towards hiding it.
  const bypass =
    status.bypass === true || status.enforce === false || status.edition === BYPASS_EDITION;

  if (bypass) {
    return {
      unlocked: true,
      bypass: true,
      showBypassBanner: true,
      invalid: false,
      reason: null,
    };
  }

  // A message on an unlicensed status means a key was supplied and rejected (bad signature,
  // expired, seat count) — as opposed to no key having been entered at all.
  const invalid = !status.licensed && Boolean(status.message);
  return {
    unlocked: status.licensed,
    bypass: false,
    showBypassBanner: false,
    invalid,
    reason: status.licensed ? null : (status.message ?? null),
  };
}

/** Formats a license key for display. Never render the full key back to the user's screen. */
export function maskLicenseKey(key: string | null | undefined): string {
  if (!key) return '—';
  const trimmed = key.trim();
  if (trimmed.length <= 8) return '••••';
  return `${trimmed.slice(0, 4)}••••${trimmed.slice(-4)}`;
}

/** Cheap client-side shape check so we do not round-trip an obviously malformed paste. */
export function looksLikeLicenseKey(value: string): boolean {
  const trimmed = value.trim();
  if (trimmed.length < 16) return false;
  // payload.signature, both base64url.
  return /^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/.test(trimmed);
}

export interface LicenseContextValue extends LicenseAccess {
  status: LicenseStatus | null;
  loading: boolean;
  error: Error | null;
  /** Submit a key; resolves once the status has been refreshed. */
  activate: (licenseKey: string) => Promise<void>;
  refresh: () => Promise<void>;
}

export const LicenseContext = createContext<LicenseContextValue | null>(null);

export function useLicense(): LicenseContextValue {
  const context = useContext(LicenseContext);
  if (!context) throw new Error('useLicense must be used inside <LicenseGate>');
  return context;
}
