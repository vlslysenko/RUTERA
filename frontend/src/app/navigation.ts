import type { ComponentType } from 'react';
import {
  IconChartBar,
  IconClockHour4,
  IconFileInvoice,
  IconFileText,
  IconGasStation,
  IconHome,
  IconMap,
  IconRoute,
  IconSettings,
  IconTruck,
  IconUsers,
  type IconProps,
} from '@tabler/icons-react';
import type { Role, ScreenKey } from './types';

export type NavIcon = ComponentType<IconProps>;

export interface NavItem {
  key: ScreenKey | 'settings';
  path: string;
  icon: NavIcon;
  /** i18n key of the label (flat key, same dictionary as the backend). */
  labelKey: string;
  /** Roles that see the item. Undefined = everyone. */
  roles?: readonly Role[];
}

export interface NavGroup {
  key: string;
  /** Group heading; the first group has none (as in the mockups). */
  labelKey?: string;
  items: NavItem[];
}

const ALL: readonly Role[] = ['SUPERUSER', 'OWNER', 'DISPATCHER', 'LOGISTICIAN', 'ACCOUNTANT', 'DRIVER', 'CUSTOM'];
const OPS: readonly Role[] = ['SUPERUSER', 'OWNER', 'DISPATCHER', 'LOGISTICIAN', 'CUSTOM'];
const MONEY: readonly Role[] = ['SUPERUSER', 'OWNER', 'ACCOUNTANT', 'CUSTOM'];

export const SCREENS: Record<ScreenKey, NavItem> = {
  overview: { key: 'overview', path: '/overview', icon: IconHome, labelKey: 'nav.overview', roles: ALL },
  map: { key: 'map', path: '/map', icon: IconMap, labelKey: 'nav.map', roles: OPS },
  trips: { key: 'trips', path: '/trips', icon: IconRoute, labelKey: 'nav.trips', roles: OPS },
  fleet: { key: 'fleet', path: '/fleet', icon: IconTruck, labelKey: 'nav.fleet', roles: OPS },
  drivers: { key: 'drivers', path: '/drivers', icon: IconUsers, labelKey: 'nav.drivers', roles: OPS },
  tacho: {
    key: 'tacho',
    path: '/tacho',
    icon: IconClockHour4,
    labelKey: 'nav.tacho',
    roles: ['SUPERUSER', 'OWNER', 'DISPATCHER', 'CUSTOM'],
  },
  documents: {
    key: 'documents',
    path: '/documents',
    icon: IconFileText,
    labelKey: 'nav.documents',
    roles: [...OPS, 'ACCOUNTANT'],
  },
  finance: { key: 'finance', path: '/finance', icon: IconFileInvoice, labelKey: 'nav.finance', roles: MONEY },
  fuel: { key: 'fuel', path: '/fuel', icon: IconGasStation, labelKey: 'nav.fuel', roles: [...OPS, 'ACCOUNTANT'] },
  reports: { key: 'reports', path: '/reports', icon: IconChartBar, labelKey: 'nav.reports', roles: [...OPS, 'ACCOUNTANT'] },
};

export const SETTINGS_ITEM: NavItem = {
  key: 'settings',
  path: '/settings',
  icon: IconSettings,
  labelKey: 'nav.settings',
};

export const NAV_GROUPS: NavGroup[] = [
  { key: 'work', items: [SCREENS.overview, SCREENS.map, SCREENS.trips] },
  { key: 'resources', labelKey: 'nav.group.resources', items: [SCREENS.fleet, SCREENS.drivers, SCREENS.tacho] },
  {
    key: 'documents',
    labelKey: 'nav.group.documents',
    items: [SCREENS.documents, SCREENS.finance, SCREENS.fuel, SCREENS.reports],
  },
];

/** Default start screen per role (REQUIREMENTS.md, section 15). The user can override it in the profile. */
export const DEFAULT_START_SCREEN: Record<Role, ScreenKey> = {
  SUPERUSER: 'overview',
  OWNER: 'overview',
  DISPATCHER: 'map',
  LOGISTICIAN: 'trips',
  ACCOUNTANT: 'finance',
  DRIVER: 'overview',
  API: 'overview',
  CUSTOM: 'overview',
};

export function canSee(item: NavItem, role: Role): boolean {
  return !item.roles || item.roles.includes(role);
}

export function visibleGroups(role: Role): NavGroup[] {
  return NAV_GROUPS.map((g) => ({ ...g, items: g.items.filter((i) => canSee(i, role)) })).filter(
    (g) => g.items.length > 0,
  );
}

export function visibleScreens(role: Role): NavItem[] {
  return visibleGroups(role).flatMap((g) => g.items);
}

export function startScreenPath(role: Role, preferred: ScreenKey | null): string {
  const key = preferred && canSee(SCREENS[preferred], role) ? preferred : DEFAULT_START_SCREEN[role];
  const item = SCREENS[key];
  return canSee(item, role) ? item.path : SCREENS.overview.path;
}
