import {
  ActionIcon,
  Button,
  Card,
  createTheme,
  Input,
  Menu,
  Paper,
  Table,
  Tooltip,
  type CSSVariablesResolver,
  type MantineColorsTuple,
} from '@mantine/core';

/*
 * Direction A "Dispatch room" (REQUIREMENTS.md, section 15):
 * dense light UI, cool gray-blue neutrals, dark sidebar, blue accent #1F5FBF,
 * IBM Plex Sans + IBM Plex Mono, 13 px base, 32 px table row, 4 px radius, borders instead of shadows.
 * Every color, font and spacing lives here as a theme token; components never hard-code them.
 */

const blue: MantineColorsTuple = [
  '#E3ECFA', '#C9DAF5', '#A3BEEB', '#7CA1E0', '#5684D3', '#3A6FC8', '#1F5FBF', '#1A50A2', '#154285', '#103468',
];
// Cool neutrals: 0 input bg, 1 page bg, 3 line, 6 muted text, 9 text.
const gray: MantineColorsTuple = [
  '#F7F9FB', '#EEF1F5', '#E3E8EE', '#D5DBE3', '#B9C2CD', '#8C97A5', '#5F6B7A', '#46515F', '#2F3845', '#1A2433',
];
// Status colors are separate from the accent and are always paired with an icon or a word.
const green: MantineColorsTuple = [
  '#E1F4E1', '#C3E9C3', '#9BDB9B', '#6FCB6F', '#45BA45', '#0CA30C', '#0B8F0B', '#146C14', '#0F520F', '#0A3A0A',
];
const yellow: MantineColorsTuple = [
  '#FFF0D1', '#FFE2A8', '#FBD07A', '#F5BD4E', '#EEAC2A', '#E59E0B', '#C98A09', '#A26F07', '#7A4E00', '#573700',
];
const red: MantineColorsTuple = [
  '#FBE2E2', '#F6C5C5', '#EFA0A0', '#E67A7A', '#DC5A5A', '#D03B3B', '#B93232', '#9E1F1F', '#7D1818', '#5C1111',
];

const SANS = "'IBM Plex Sans', system-ui, -apple-system, 'Segoe UI', Roboto, sans-serif";
const MONO = "'IBM Plex Mono', ui-monospace, SFMono-Regular, Menlo, Consolas, monospace";

export const theme = createTheme({
  primaryColor: 'blue',
  primaryShade: 6,
  colors: { blue, gray, green, yellow, red },
  white: '#FFFFFF',
  black: '#1A2433',

  fontFamily: SANS,
  fontFamilyMonospace: MONO,
  headings: {
    fontFamily: SANS,
    fontWeight: '600',
    sizes: {
      h1: { fontSize: '19px', lineHeight: '1.2' },
      h2: { fontSize: '16px', lineHeight: '1.25' },
      h3: { fontSize: '14px', lineHeight: '1.3' },
      h4: { fontSize: '13px', lineHeight: '1.3' },
    },
  },
  fontSizes: { xs: '11px', sm: '12px', md: '13px', lg: '15px', xl: '18px' },
  lineHeights: { xs: '1.3', sm: '1.35', md: '1.35', lg: '1.4', xl: '1.45' },
  fontWeights: { regular: '400', medium: '500', bold: '600' },

  radius: { xs: '2px', sm: '4px', md: '6px', lg: '8px', xl: '12px' },
  defaultRadius: 'sm',
  spacing: { xs: '6px', sm: '10px', md: '12px', lg: '16px', xl: '24px' },
  shadows: {
    xs: 'none',
    sm: 'none',
    md: '0 2px 8px rgba(26, 36, 51, 0.10)',
    lg: '0 4px 16px rgba(26, 36, 51, 0.12)',
    xl: '0 8px 28px rgba(26, 36, 51, 0.14)',
  },
  cursorType: 'pointer',
  focusRing: 'auto',

  other: {
    // Sidebar (dark) tokens
    sideBg: '#1E2A3A',
    sideFg: '#C9D3DF',
    sideActive: '#2E3F56',
    sideAccent: '#9CC3F5',
    sideLine: '#2E3C50',
    sideWidth: 208,
    sideWidthCollapsed: 52,
    // Shell
    topBarHeight: 46,
    asideWidth: 420,
    rowHeight: '32px',
    // Status (bg / fg / solid)
    statusGoodBg: '#E1F4E1',
    statusGoodFg: '#146C14',
    statusGood: '#0CA30C',
    statusWarnBg: '#FFF0D1',
    statusWarnFg: '#7A4E00',
    statusWarn: '#E59E0B',
    statusCritBg: '#FBE2E2',
    statusCritFg: '#9E1F1F',
    statusCrit: '#D03B3B',
    statusInfoBg: '#E3ECFA',
    statusInfoFg: '#1F4E9C',
    statusInfo: '#1F5FBF',
  },

  components: {
    Button: Button.extend({
      defaultProps: { size: 'sm' },
      vars: (_theme, props) =>
        !props.size || props.size === 'sm'
          ? { root: { '--button-height': '30px', '--button-padding-x': '12px', '--button-fz': '13px' } }
          : { root: {} },
    }),
    Input: Input.extend({
      vars: (_theme, props) =>
        !props.size || props.size === 'sm'
          ? { wrapper: { '--input-height': '32px', '--input-fz': '13px', '--input-padding-x': '10px' } }
          : { wrapper: {} },
    }),
    ActionIcon: ActionIcon.extend({
      defaultProps: { variant: 'subtle', color: 'gray', size: 'md' },
    }),
    Card: Card.extend({
      defaultProps: { withBorder: true, shadow: 'none', radius: 'sm', padding: 'md' },
    }),
    Paper: Paper.extend({
      defaultProps: { withBorder: true, shadow: 'none', radius: 'sm' },
    }),
    Table: Table.extend({
      defaultProps: { verticalSpacing: 3, horizontalSpacing: 'md', highlightOnHover: true },
    }),
    Tooltip: Tooltip.extend({
      defaultProps: { openDelay: 300, withArrow: false, fz: 'sm' },
    }),
    Menu: Menu.extend({
      defaultProps: { shadow: 'md', radius: 'sm', withinPortal: true },
    }),
  },
});

/** Exposes theme.other tokens as CSS variables (--rutera-*) for CSS modules. */
export const cssVariablesResolver: CSSVariablesResolver = (t) => ({
  variables: {
    '--rutera-side-bg': String(t.other.sideBg),
    '--rutera-side-fg': String(t.other.sideFg),
    '--rutera-side-active': String(t.other.sideActive),
    '--rutera-side-accent': String(t.other.sideAccent),
    '--rutera-side-line': String(t.other.sideLine),
    '--rutera-row-height': String(t.other.rowHeight),
    '--rutera-status-good-bg': String(t.other.statusGoodBg),
    '--rutera-status-good-fg': String(t.other.statusGoodFg),
    '--rutera-status-good': String(t.other.statusGood),
    '--rutera-status-warn-bg': String(t.other.statusWarnBg),
    '--rutera-status-warn-fg': String(t.other.statusWarnFg),
    '--rutera-status-warn': String(t.other.statusWarn),
    '--rutera-status-crit-bg': String(t.other.statusCritBg),
    '--rutera-status-crit-fg': String(t.other.statusCritFg),
    '--rutera-status-crit': String(t.other.statusCrit),
    '--rutera-status-info-bg': String(t.other.statusInfoBg),
    '--rutera-status-info-fg': String(t.other.statusInfoFg),
    '--rutera-status-info': String(t.other.statusInfo),
  },
  light: {
    '--mantine-color-text': '#1A2433',
    '--mantine-color-dimmed': '#5F6B7A',
    '--mantine-color-default-border': '#D5DBE3',
    '--mantine-color-placeholder': '#8C97A5',
  },
  dark: {},
});
