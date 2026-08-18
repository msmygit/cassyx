import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react';
import CssBaseline from '@mui/material/CssBaseline';
import { ThemeProvider } from '@mui/material/styles';
import {
  ColorModeContext,
  readStoredPreference,
  resolveMode,
  storePreference,
  type ColorModeContextValue,
  type ColorModePreference,
} from './colorMode';
import { createCassyxTheme } from './theme';

const DARK_QUERY = '(prefers-color-scheme: dark)';

function systemPrefersDark(): boolean {
  return globalThis.matchMedia?.(DARK_QUERY).matches ?? false;
}

export interface CassyxThemeProviderProps {
  children: ReactNode;
  /** Override the initial preference — used by tests and Storybook-style harnesses. */
  initialPreference?: ColorModePreference;
}

/**
 * Provides the cassyx MUI theme and the light/dark switch.
 *
 * Defaults to `system` and listens for OS changes at runtime, so the app follows the user's
 * machine without them having to hunt for a setting. (The prior art had no dark mode at all.)
 */
export function CassyxThemeProvider({ children, initialPreference }: CassyxThemeProviderProps) {
  const [preference, setPreferenceState] = useState<ColorModePreference>(
    () => initialPreference ?? readStoredPreference(),
  );
  const [prefersDark, setPrefersDark] = useState<boolean>(systemPrefersDark);

  useEffect(() => {
    const media = globalThis.matchMedia?.(DARK_QUERY);
    if (!media) return;
    const listener = (event: MediaQueryListEvent) => setPrefersDark(event.matches);
    media.addEventListener('change', listener);
    return () => media.removeEventListener('change', listener);
  }, []);

  const mode = resolveMode(preference, prefersDark);

  const setPreference = useCallback((next: ColorModePreference) => {
    setPreferenceState(next);
    storePreference(next);
  }, []);

  const toggle = useCallback(() => {
    setPreferenceState((current) => {
      const next: ColorModePreference =
        resolveMode(current, systemPrefersDark()) === 'dark' ? 'light' : 'dark';
      storePreference(next);
      return next;
    });
  }, []);

  const theme = useMemo(() => createCassyxTheme(mode), [mode]);

  const value = useMemo<ColorModeContextValue>(
    () => ({ preference, mode, setPreference, toggle }),
    [preference, mode, setPreference, toggle],
  );

  return (
    <ColorModeContext.Provider value={value}>
      <ThemeProvider theme={theme}>
        <CssBaseline />
        {children}
      </ThemeProvider>
    </ColorModeContext.Provider>
  );
}
