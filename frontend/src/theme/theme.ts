import { createTheme, type Theme, type ThemeOptions } from '@mui/material/styles';
import { brand, shape, typography } from './tokens';

export type ColorMode = 'light' | 'dark';

declare module '@mui/material/styles' {
  interface Palette {
    /** Surface used by the shell chrome (connection bar, tab strip, sidebar). */
    chrome: { bar: string; sidebar: string; border: string; hover: string };
    /** Semantic colours for CQL object kinds in the schema tree. */
    cql: {
      keyspace: string;
      table: string;
      view: string;
      index: string;
      type: string;
      vector: string;
      system: string;
    };
  }
  interface PaletteOptions {
    chrome?: { bar: string; sidebar: string; border: string; hover: string };
    cql?: {
      keyspace: string;
      table: string;
      view: string;
      index: string;
      type: string;
      vector: string;
      system: string;
    };
  }
}

function paletteFor(mode: ColorMode): ThemeOptions['palette'] {
  const dark = mode === 'dark';
  return {
    mode,
    primary: {
      main: dark ? brand.teal[300] : brand.teal[600],
      light: dark ? brand.teal[200] : brand.teal[400],
      dark: dark ? brand.teal[500] : brand.teal[800],
      contrastText: dark ? brand.slate[950] : brand.slate[0],
    },
    secondary: {
      main: dark ? brand.violet[300] : brand.violet[600],
      light: dark ? brand.violet[200] : brand.violet[400],
      dark: dark ? brand.violet[500] : brand.violet[800],
      contrastText: dark ? brand.slate[950] : brand.slate[0],
    },
    warning: {
      main: dark ? brand.amber[300] : brand.amber[500],
      dark: brand.amber[700],
      light: brand.amber[100],
      contrastText: brand.slate[950],
    },
    error: { main: brand.danger },
    success: { main: brand.success },
    info: { main: brand.info },
    background: {
      default: dark ? brand.slate[900] : brand.slate[25],
      paper: dark ? brand.slate[850] : brand.slate[0],
    },
    text: {
      primary: dark ? brand.slate[50] : brand.slate[800],
      secondary: dark ? brand.slate[300] : brand.slate[500],
    },
    divider: dark ? brand.slate[700] : brand.slate[100],
    chrome: {
      bar: dark ? brand.slate[950] : brand.slate[50],
      sidebar: dark ? brand.slate[900] : brand.slate[25],
      border: dark ? brand.slate[700] : brand.slate[200],
      hover: dark ? brand.slate[800] : brand.slate[100],
    },
    cql: {
      keyspace: dark ? brand.teal[300] : brand.teal[700],
      table: dark ? brand.slate[100] : brand.slate[700],
      view: dark ? brand.violet[200] : brand.violet[600],
      index: dark ? brand.amber[300] : brand.amber[700],
      type: dark ? brand.teal[200] : brand.teal[600],
      vector: dark ? brand.violet[300] : brand.violet[500],
      system: dark ? brand.slate[400] : brand.slate[400],
    },
  };
}

/** Build the cassyx MUI theme for a colour mode. */
export function createCassyxTheme(mode: ColorMode): Theme {
  const dark = mode === 'dark';
  return createTheme({
    palette: paletteFor(mode),
    shape: { borderRadius: shape.radius },
    typography: {
      fontFamily: typography.sans,
      fontSize: 14,
      h1: { fontSize: '1.75rem', fontWeight: 650, letterSpacing: '-0.02em' },
      h2: { fontSize: '1.375rem', fontWeight: 650, letterSpacing: '-0.015em' },
      h3: { fontSize: '1.125rem', fontWeight: 600 },
      h6: { fontSize: '0.95rem', fontWeight: 600 },
      button: { textTransform: 'none', fontWeight: 600 },
      caption: { fontSize: '0.75rem' },
    },
    components: {
      MuiCssBaseline: {
        styleOverrides: {
          ':root': { colorScheme: mode },
          body: { overflow: 'hidden' },
          '::selection': {
            background: dark ? brand.teal[700] : brand.teal[100],
          },
          /* Slim scrollbars — the shell is dense and the default macOS/Windows bars are loud. */
          '*::-webkit-scrollbar': { width: 10, height: 10 },
          '*::-webkit-scrollbar-thumb': {
            background: dark ? brand.slate[700] : brand.slate[200],
            borderRadius: 8,
            border: '2px solid transparent',
            backgroundClip: 'content-box',
          },
          '*::-webkit-scrollbar-thumb:hover': {
            background: dark ? brand.slate[600] : brand.slate[300],
            backgroundClip: 'content-box',
          },
        },
      },
      MuiButton: {
        defaultProps: { disableElevation: true },
        styleOverrides: { root: { borderRadius: shape.radiusSm } },
      },
      MuiTextField: { defaultProps: { size: 'small' } },
      MuiSelect: { defaultProps: { size: 'small' } },
      MuiTooltip: {
        defaultProps: { arrow: true },
        styleOverrides: { tooltip: { fontSize: '0.75rem' } },
      },
      MuiPaper: { styleOverrides: { root: { backgroundImage: 'none' } } },
      MuiChip: { styleOverrides: { root: { borderRadius: shape.radiusSm, fontWeight: 600 } } },
      MuiAlert: { styleOverrides: { root: { borderRadius: shape.radiusSm } } },
    },
  });
}

export const STORAGE_KEY_COLOR_MODE = 'cassyx.colorMode';
