/**
 * cassyx design tokens.
 *
 * Identity brief: the predecessor prototype (CQLens) leaned on a lens/magnifier metaphor in stock
 * MUI blue — a "look at the data" idea. cassyx is a *data mover* as much as a viewer, so the
 * identity is built on the Cassandra token ring: a ring with a chord cutting across it, which
 * doubles as the `x` in the wordmark. Deliberately not blue, and deliberately not a magnifier.
 *
 * Palette: teal-cyan primary ("token ring"), violet secondary ("vector"), warm amber for the
 * bypass/warning state so an unlicensed-bypass banner is impossible to overlook.
 *
 * Both modes are first-class. The prior art shipped light-only; dark mode is the default here
 * because this is a tool people keep open next to a terminal all day.
 */

export const brand = {
  /** Primary — token ring teal. */
  teal: {
    50: '#e2fbf7',
    100: '#b7f4ea',
    200: '#83ebdc',
    300: '#4ee0cd',
    400: '#21d3bd',
    500: '#0fb3a1',
    600: '#0c9184',
    700: '#0a7268',
    800: '#08564f',
    900: '#053a35',
  },
  /** Secondary — vector violet. */
  violet: {
    50: '#f0ecff',
    100: '#dcd2ff',
    200: '#bfaeff',
    300: '#a087ff',
    400: '#8767f7',
    500: '#6f4ee0',
    600: '#5a3cc0',
    700: '#472f99',
    800: '#352374',
    900: '#241750',
  },
  /** Warning / bypass amber. */
  amber: {
    100: '#ffeec2',
    300: '#ffd166',
    500: '#f2a900',
    700: '#b87d00',
    900: '#6b4900',
  },
  /** Neutral ramp used for surfaces and the schema tree chrome. */
  slate: {
    0: '#ffffff',
    25: '#fbfcfd',
    50: '#f4f6f8',
    100: '#e7ebef',
    200: '#d2d9e0',
    300: '#aeb9c4',
    400: '#7d8b99',
    500: '#5a6875',
    600: '#414d59',
    700: '#2c3641',
    800: '#1c242d',
    850: '#151b22',
    900: '#0f1419',
    950: '#0a0e12',
  },
  danger: '#e5484d',
  success: '#30a46c',
  info: '#0091ff',
} as const;

/** Font stacks. Monospace is load-bearing: CQL editor, grid cells, blob/uuid rendering. */
export const typography = {
  sans: '"Inter", "Inter var", -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif',
  mono: '"JetBrains Mono", "SFMono-Regular", ui-monospace, Menlo, Consolas, monospace',
} as const;

/** Shell geometry shared by the layout components. */
export const layout = {
  connectionBarHeight: 52,
  tabBarHeight: 38,
  statusBarHeight: 24,
  sidebarDefaultWidth: 300,
  sidebarMinWidth: 200,
  sidebarMaxWidth: 640,
  bannerHeight: 34,
} as const;

export const shape = {
  radius: 8,
  radiusSm: 6,
} as const;
