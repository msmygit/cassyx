import { createContext, useContext } from 'react';
import type { ColorMode } from './theme';
import { STORAGE_KEY_COLOR_MODE } from './theme';

/** `system` follows the OS `prefers-color-scheme`; the other two pin the mode. */
export type ColorModePreference = ColorMode | 'system';

export interface ColorModeContextValue {
  /** What the user chose. */
  preference: ColorModePreference;
  /** What is actually rendered after resolving `system`. */
  mode: ColorMode;
  setPreference: (preference: ColorModePreference) => void;
  /** Convenience for the toolbar button: flips between explicit light and dark. */
  toggle: () => void;
}

export const ColorModeContext = createContext<ColorModeContextValue | null>(null);

export function useColorMode(): ColorModeContextValue {
  const context = useContext(ColorModeContext);
  if (!context) {
    throw new Error('useColorMode must be used inside <CassyxThemeProvider>');
  }
  return context;
}

export function readStoredPreference(storage?: Storage): ColorModePreference {
  try {
    const store = storage ?? globalThis.localStorage;
    const value = store?.getItem(STORAGE_KEY_COLOR_MODE);
    if (value === 'light' || value === 'dark' || value === 'system') return value;
  } catch {
    // Private-mode / disabled storage: fall through to the default.
  }
  return 'system';
}

export function storePreference(preference: ColorModePreference, storage?: Storage): void {
  try {
    const store = storage ?? globalThis.localStorage;
    store?.setItem(STORAGE_KEY_COLOR_MODE, preference);
  } catch {
    // Non-fatal.
  }
}

export function resolveMode(
  preference: ColorModePreference,
  systemPrefersDark: boolean,
): ColorMode {
  if (preference === 'system') return systemPrefersDark ? 'dark' : 'light';
  return preference;
}
