import { describe, expect, it } from 'vitest';
import { deriveLicenseAccess, looksLikeLicenseKey, maskLicenseKey } from './licenseModel';
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
