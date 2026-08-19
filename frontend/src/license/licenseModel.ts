/**
 * Pure license logic (plan §9.1 / §9.4 / §9.5). No React, no network — so it can be tested
 * exhaustively.
 *
 * The critical rule encoded here: when the backend reports `edition: "unlicensed-bypass"` (i.e.
 * `CASSYX_LICENSE_ENFORCE=false`), the app is fully unlocked **and** a persistent banner must be
 * visible. A bypassed instance must never be mistakable for a paid one.
 *
 * The second rule: `status.state` is not just a boolean flip. `EXPIRED` and `UPGRADE_REQUIRED`
 * are genuine licences that still deserve to be treated as customers, not intruders — collapsing
 * them into the same "invalid" bucket as a tampered key throws away the product's only conversion
 * moment (plan §9.4).
 */
import { createContext, useContext } from 'react';
import type { LicenseStatus } from '../api/types';

/** Discriminates the reason `unlocked` is false (or, for VALID/BYPASS, why it is true). */
export type LicenseAccessDetail =
  | { state: 'VALID' }
  | { state: 'BYPASS' }
  /** Genuine, signed licence, past `expires`. The backend retains name/email for the checkout prefill. */
  | { state: 'EXPIRED'; name: string | null; email: string | null; expires: string | null }
  /** No key supplied at all — first run. */
  | { state: 'ABSENT' }
  /** Genuine, unexpired licence bought for an older major. It still works on the version it was sold for. */
  | { state: 'UPGRADE_REQUIRED'; scope: number | null }
  | {
      state: 'MALFORMED';
      /** True when the server has no public key configured — an operator problem, not a buyer one. */
      operatorIssue: boolean;
      message: string | null;
    }
  /** Well-formed but not signed by us — likely a typo or a tampered/pirated key. */
  | { state: 'INVALID_SIGNATURE'; message: string | null }
  /** No `status`, or a `state` value this build does not recognise (older backend). */
  | { state: 'UNKNOWN'; message: string | null };

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
  /** Convenience flag, mirrors `status.trial`. */
  trial: boolean;
  /** Whole days left on a trial/expiring licence; null when perpetual or unknown. */
  daysRemaining: number | null;
  /** The discriminated detail `ActivationScreen` branches on. */
  detail: LicenseAccessDetail;
}

const BYPASS_EDITION = 'unlicensed-bypass';

/** Server text for the unconfigured-public-key case (LicenseController#check) is operator-facing. */
function isOperatorConfigIssue(message: string | null): boolean {
  if (!message) return false;
  return /public-key|PUBLIC_KEY/.test(message);
}

export function deriveLicenseAccess(status: LicenseStatus | null | undefined): LicenseAccess {
  if (!status) {
    return {
      unlocked: false,
      bypass: false,
      showBypassBanner: false,
      invalid: false,
      reason: null,
      trial: false,
      daysRemaining: null,
      detail: { state: 'UNKNOWN', message: null },
    };
  }

  // Belt and braces: treat ANY of the three signals as bypass. `bypass: true`, `enforce: false`
  // and the sentinel edition are supposed to travel together, but a mismatch must fail safe
  // towards SHOWING the banner — never towards hiding it.
  const bypass =
    status.bypass === true || status.enforce === false || status.edition === BYPASS_EDITION;

  const trial = status.trial === true;
  const daysRemaining = status.daysRemaining ?? null;

  if (bypass) {
    return {
      unlocked: true,
      bypass: true,
      showBypassBanner: true,
      invalid: false,
      reason: null,
      trial,
      daysRemaining,
      detail: { state: 'BYPASS' },
    };
  }

  const message = status.message ?? null;

  switch (status.state) {
    case 'VALID':
      return {
        unlocked: true,
        bypass: false,
        showBypassBanner: false,
        invalid: false,
        reason: null,
        trial,
        daysRemaining,
        detail: { state: 'VALID' },
      };

    case 'EXPIRED':
      return {
        unlocked: false,
        bypass: false,
        showBypassBanner: false,
        invalid: true,
        reason: message,
        trial,
        daysRemaining,
        detail: {
          state: 'EXPIRED',
          name: status.name ?? null,
          email: status.email ?? null,
          expires: status.expires ?? null,
        },
      };

    case 'ABSENT':
      return {
        unlocked: false,
        bypass: false,
        showBypassBanner: false,
        invalid: false,
        reason: message,
        trial,
        daysRemaining,
        detail: { state: 'ABSENT' },
      };

    case 'UPGRADE_REQUIRED':
      return {
        unlocked: false,
        bypass: false,
        showBypassBanner: false,
        invalid: true,
        reason: message,
        trial,
        daysRemaining,
        detail: { state: 'UPGRADE_REQUIRED', scope: status.scope ?? null },
      };

    case 'MALFORMED':
      return {
        unlocked: false,
        bypass: false,
        showBypassBanner: false,
        invalid: true,
        reason: message,
        trial,
        daysRemaining,
        detail: {
          state: 'MALFORMED',
          operatorIssue: isOperatorConfigIssue(message),
          message,
        },
      };

    case 'INVALID_SIGNATURE':
      return {
        unlocked: false,
        bypass: false,
        showBypassBanner: false,
        invalid: true,
        reason: message,
        trial,
        daysRemaining,
        detail: { state: 'INVALID_SIGNATURE', message },
      };

    default: {
      // Older backend that does not send `state` yet, or a value this build does not recognise:
      // fall back to today's licensed/message behaviour rather than crashing or rendering blank.
      const invalid = !status.licensed && Boolean(message);
      return {
        unlocked: status.licensed,
        bypass: false,
        showBypassBanner: false,
        invalid,
        reason: status.licensed ? null : message,
        trial,
        daysRemaining,
        detail: { state: 'UNKNOWN', message: status.licensed ? null : message },
      };
    }
  }
}

/** How urgent the trial countdown should look. `daysRemaining` is inclusive of today (plan §9.4). */
export type TrialUrgency = 'normal' | 'warning' | 'critical';

export function trialUrgency(daysRemaining: number | null): TrialUrgency {
  if (daysRemaining === null) return 'normal';
  if (daysRemaining <= 2) return 'critical';
  if (daysRemaining <= 5) return 'warning';
  return 'normal';
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
