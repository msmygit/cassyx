import { describe, expect, it } from 'vitest';
import {
  deriveLicenseAccess,
  looksLikeLicenseKey,
  maskLicenseKey,
  trialUrgency,
} from './licenseModel';
import type { LicenseStatus } from '../api/types';

function status(partial: Partial<LicenseStatus>): LicenseStatus {
  return { licensed: false, enforce: true, bypass: false, edition: 'none', ...partial };
}

describe('deriveLicenseAccess', () => {
  it('locks the app when there is no status yet', () => {
    const access = deriveLicenseAccess(null);
    expect(access.unlocked).toBe(false);
    expect(access.showBypassBanner).toBe(false);
  });

  it('unlocks a valid license without a banner', () => {
    const access = deriveLicenseAccess(status({ licensed: true, edition: 'standard' }));
    expect(access).toMatchObject({ unlocked: true, bypass: false, showBypassBanner: false });
  });

  it('unlocks AND banners the unlicensed-bypass edition', () => {
    const access = deriveLicenseAccess(
      status({ licensed: false, bypass: true, enforce: false, edition: 'unlicensed-bypass' }),
    );
    expect(access.unlocked).toBe(true);
    expect(access.bypass).toBe(true);
    expect(access.showBypassBanner).toBe(true);
  });

  it('fails safe towards the banner when only enforce=false is reported', () => {
    const access = deriveLicenseAccess(
      status({ licensed: true, edition: 'standard', enforce: false }),
    );
    expect(access.showBypassBanner).toBe(true);
  });

  it('fails safe towards the banner when only the sentinel edition is reported', () => {
    const access = deriveLicenseAccess(status({ edition: 'unlicensed-bypass' }));
    expect(access.unlocked).toBe(true);
    expect(access.showBypassBanner).toBe(true);
  });

  it('blocks a rejected key and explains why', () => {
    const access = deriveLicenseAccess(
      status({ message: 'Signature verification failed for this key.' }),
    );
    expect(access.unlocked).toBe(false);
    expect(access.invalid).toBe(true);
    expect(access.reason).toMatch(/signature/i);
  });

  it('treats “no key entered at all” as unlicensed but not invalid', () => {
    const access = deriveLicenseAccess(status({}));
    expect(access.unlocked).toBe(false);
    expect(access.invalid).toBe(false);
    expect(access.reason).toBeNull();
  });

  it('surfaces an expiry message verbatim', () => {
    expect(
      deriveLicenseAccess(status({ message: 'This license key has expired.' })).reason,
    ).toMatch(/expired/i);
  });

  it('never shows a rejection reason once the license is valid', () => {
    expect(
      deriveLicenseAccess(status({ licensed: true, edition: 'standard', message: 'stale note' }))
        .reason,
    ).toBeNull();
  });
});

describe('deriveLicenseAccess — state discrimination (plan §9.4/§9.5)', () => {
  it('VALID: unlocked, no banner, no reason', () => {
    const access = deriveLicenseAccess(
      status({ licensed: true, edition: 'standard', state: 'VALID' }),
    );
    expect(access).toMatchObject({ unlocked: true, invalid: false, reason: null });
    expect(access.detail).toEqual({ state: 'VALID' });
  });

  it('BYPASS: unlocked and banners even when reported via `state` alone', () => {
    const access = deriveLicenseAccess(
      status({ bypass: true, enforce: false, edition: 'unlicensed-bypass', state: 'BYPASS' }),
    );
    expect(access.unlocked).toBe(true);
    expect(access.showBypassBanner).toBe(true);
    expect(access.detail).toEqual({ state: 'BYPASS' });
  });

  it('EXPIRED: locked but retains name/email/expires for the checkout prefill', () => {
    const access = deriveLicenseAccess(
      status({
        state: 'EXPIRED',
        message: 'This license expired on 2026-08-01.',
        name: 'Acme Corp',
        email: 'ops@acme.example',
        expires: '2026-08-01',
      }),
    );
    expect(access.unlocked).toBe(false);
    expect(access.invalid).toBe(true);
    expect(access.detail).toEqual({
      state: 'EXPIRED',
      name: 'Acme Corp',
      email: 'ops@acme.example',
      expires: '2026-08-01',
    });
  });

  it('ABSENT: locked but NOT flagged invalid — first run, not an error', () => {
    const access = deriveLicenseAccess(status({ state: 'ABSENT' }));
    expect(access.unlocked).toBe(false);
    expect(access.invalid).toBe(false);
    expect(access.detail).toEqual({ state: 'ABSENT' });
  });

  it('UPGRADE_REQUIRED: locked, invites purchase, carries scope', () => {
    const access = deriveLicenseAccess(
      status({ state: 'UPGRADE_REQUIRED', scope: 1, message: 'Licensed for major version 1.' }),
    );
    expect(access.unlocked).toBe(false);
    expect(access.invalid).toBe(true);
    expect(access.detail).toEqual({ state: 'UPGRADE_REQUIRED', scope: 1 });
  });

  it('MALFORMED: distinguishes an operator config problem from a bad paste', () => {
    const operatorIssue = deriveLicenseAccess(
      status({
        state: 'MALFORMED',
        message:
          'cassyx.license.public-key is not configured, so no licence can be verified. Set CASSYX_LICENSE_PUBLIC_KEY.',
      }),
    );
    expect(operatorIssue.detail).toMatchObject({ state: 'MALFORMED', operatorIssue: true });

    const badPaste = deriveLicenseAccess(
      status({ state: 'MALFORMED', message: 'Malformed license key' }),
    );
    expect(badPaste.detail).toMatchObject({ state: 'MALFORMED', operatorIssue: false });
  });

  it('INVALID_SIGNATURE: locked, neutral detail, no operator framing', () => {
    const access = deriveLicenseAccess(
      status({
        state: 'INVALID_SIGNATURE',
        message: 'Signature verification failed for this key.',
      }),
    );
    expect(access.unlocked).toBe(false);
    expect(access.invalid).toBe(true);
    expect(access.detail).toEqual({
      state: 'INVALID_SIGNATURE',
      message: 'Signature verification failed for this key.',
    });
  });

  it('falls back to today’s licensed/message behaviour for an unrecognised or absent `state`', () => {
    const access = deriveLicenseAccess(status({ message: 'Signature mismatch.' }));
    expect(access.unlocked).toBe(false);
    expect(access.invalid).toBe(true);
    expect(access.detail).toEqual({ state: 'UNKNOWN', message: 'Signature mismatch.' });
  });

  it('null status is treated as UNKNOWN, not a crash', () => {
    expect(deriveLicenseAccess(null).detail).toEqual({ state: 'UNKNOWN', message: null });
    expect(deriveLicenseAccess(undefined).unlocked).toBe(false);
  });

  it('surfaces trial and daysRemaining regardless of state', () => {
    const access = deriveLicenseAccess(
      status({ licensed: true, edition: 'trial', state: 'VALID', trial: true, daysRemaining: 3 }),
    );
    expect(access.trial).toBe(true);
    expect(access.daysRemaining).toBe(3);
  });
});

describe('trialUrgency', () => {
  it('escalates as days run out', () => {
    expect(trialUrgency(null)).toBe('normal');
    expect(trialUrgency(14)).toBe('normal');
    expect(trialUrgency(5)).toBe('warning');
    expect(trialUrgency(2)).toBe('critical');
    expect(trialUrgency(0)).toBe('critical');
  });
});

describe('license key helpers', () => {
  it('masks all but the first and last four characters', () => {
    expect(maskLicenseKey('eyJsaWMiOiJDU1gtMTIzNCJ9.c2ln')).toBe('eyJs••••c2ln');
    expect(maskLicenseKey('short')).toBe('••••');
    expect(maskLicenseKey(null)).toBe('—');
  });

  it('accepts only payload.signature base64url shapes', () => {
    expect(looksLikeLicenseKey('eyJsaWMiOiJDU1gtMTIzNCJ9.c2lnbmF0dXJl')).toBe(true);
    expect(looksLikeLicenseKey('too-short')).toBe(false);
    expect(looksLikeLicenseKey('no-dot-but-long-enough-string')).toBe(false);
    expect(looksLikeLicenseKey('has spaces.in the middle here')).toBe(false);
  });
});
