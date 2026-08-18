import { describe, expect, it } from 'vitest';
import * as dsbulk from './index';

/**
 * The barrel is what the parent workstream wires into routes, so a missing export is a real
 * breakage rather than a cosmetic one.
 */
describe('dsbulk barrel', () => {
  it('exports the four components', () => {
    for (const name of [
      'DsbulkSettingsForm',
      'DsbulkCommandPreview',
      'LoadJobForm',
      'CountStatisticsView',
    ] as const) {
      // `DsbulkSettingsForm` is a `memo()` object; the others are plain function components.
      expect(dsbulk[name]).toBeDefined();
    }
  });

  it('exports the catalog, the model helpers, the API and the hooks', () => {
    expect(dsbulk.DSBULK_SETTINGS.length).toBeGreaterThan(100);
    expect(dsbulk.findSetting('batch.mode')?.group).toBe('batch');
    expect(dsbulk.flattenSettings({ batch: { mode: 'DISABLED' } })).toEqual({
      'batch.mode': 'DISABLED',
    });
    expect(dsbulk.unflattenSettings({ 'batch.mode': 'DISABLED' })).toEqual({
      batch: { mode: 'DISABLED' },
    });
    expect(dsbulk.SECRET_SETTING_PATHS).toHaveLength(3);
    expect(dsbulk.dsbulkQueryKeys.all).toEqual(['bulk', 'dsbulk']);
    expect(dsbulk.deriveBulkDefaults).toBeTypeOf('function');
    expect(dsbulk.useDsbulkDefaults).toBeTypeOf('function');
  });
});
