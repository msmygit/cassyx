import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { CassyxThemeProvider } from './CassyxThemeProvider';
import { useColorMode, readStoredPreference, resolveMode, storePreference } from './colorMode';
import { createCassyxTheme, STORAGE_KEY_COLOR_MODE } from './theme';

function ModeProbe() {
  const { mode, preference, toggle, setPreference } = useColorMode();
  return (
    <div>
      <span data-testid="mode">{mode}</span>
      <span data-testid="preference">{preference}</span>
      <button onClick={toggle}>toggle</button>
      <button onClick={() => setPreference('system')}>system</button>
    </div>
  );
}

describe('createCassyxTheme', () => {
  it('produces distinct light and dark palettes', () => {
    const light = createCassyxTheme('light');
    const dark = createCassyxTheme('dark');

    expect(light.palette.mode).toBe('light');
    expect(dark.palette.mode).toBe('dark');
    expect(light.palette.background.default).not.toBe(dark.palette.background.default);
    expect(light.palette.primary.main).not.toBe(dark.palette.primary.main);
  });

  it('is not stock MUI blue — the cassyx identity is teal', () => {
    expect(createCassyxTheme('light').palette.primary.main.toLowerCase()).not.toBe('#1976d2');
  });

  it('exposes the custom chrome and CQL palettes both modes need', () => {
    for (const mode of ['light', 'dark'] as const) {
      const theme = createCassyxTheme(mode);
      expect(theme.palette.chrome.bar).toBeTruthy();
      expect(theme.palette.chrome.sidebar).toBeTruthy();
      expect(theme.palette.cql.keyspace).toBeTruthy();
      expect(theme.palette.cql.vector).toBeTruthy();
    }
  });
});

describe('colour mode preference', () => {
  it('resolves `system` against the OS preference', () => {
    expect(resolveMode('system', true)).toBe('dark');
    expect(resolveMode('system', false)).toBe('light');
    expect(resolveMode('dark', false)).toBe('dark');
    expect(resolveMode('light', true)).toBe('light');
  });

  it('round-trips through storage and defaults to `system`', () => {
    globalThis.localStorage.removeItem(STORAGE_KEY_COLOR_MODE);
    expect(readStoredPreference()).toBe('system');

    storePreference('dark');
    expect(readStoredPreference()).toBe('dark');

    globalThis.localStorage.setItem(STORAGE_KEY_COLOR_MODE, 'nonsense');
    expect(readStoredPreference()).toBe('system');
  });
});

describe('CassyxThemeProvider', () => {
  it('toggles between light and dark and persists the choice', async () => {
    const user = userEvent.setup();
    render(
      <CassyxThemeProvider initialPreference="light">
        <ModeProbe />
      </CassyxThemeProvider>,
    );

    expect(screen.getByTestId('mode')).toHaveTextContent('light');

    await user.click(screen.getByText('toggle'));
    expect(screen.getByTestId('mode')).toHaveTextContent('dark');
    expect(globalThis.localStorage.getItem(STORAGE_KEY_COLOR_MODE)).toBe('dark');

    await user.click(screen.getByText('system'));
    expect(screen.getByTestId('preference')).toHaveTextContent('system');
  });

  it('throws a clear error when useColorMode is used outside the provider', () => {
    expect(() => render(<ModeProbe />)).toThrow(/CassyxThemeProvider/);
  });
});
