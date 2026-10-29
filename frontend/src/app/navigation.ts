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
import { hasAnyPrivilege, PRIV } from '@/auth/privileges';
import type { Role, ScreenKey, User } from './types';

export type NavIcon = ComponentType<IconProps>;

export interface NavItem {
  key: ScreenKey | 'settings';
  path: string;
  icon: NavIcon;
  /** i18n key of the label (flat key, same dictionary as the backend). */
  labelKey: string;
  /** The item is shown when the user holds at least one of these privileges. Undefined = everyone. */
  privileges?: readonly string[];
}

export interface NavGroup {
  key: string;
  /** Group heading; the first group has none (as in the mockups). */
  labelKey?: string;
  items: NavItem[];
}

export const SCREENS: Record<ScreenKey, NavItem> = {
  overview: { key: 'overview', path: '/overview', icon: IconHome, labelKey: 'nav.overview' },
  map: { key: 'map', path: '/map', icon: IconMap, labelKey: 'nav.map', privileges: [PRIV.MAP_POSITION_VIEW] },
  trips: { key: 'trips', path: '/trips', icon: IconRoute, labelKey: 'nav.trips', privileges: [PRIV.TRIP_TRIP_VIEW] },
  fleet: { key: 'fleet', path: '/fleet', icon: IconTruck, labelKey: 'nav.fleet', privileges: [PRIV.FLEET_VEHICLE_VIEW] },
  drivers: { key: 'drivers', path: '/drivers', icon: IconUsers, labelKey: 'nav.drivers', privileges: [PRIV.DRIVER_VIEW] },
  tacho: { key: 'tacho', path: '/tacho', icon: IconClockHour4, labelKey: 'nav.tacho', privileges: [PRIV.TACHO_HOS_VIEW] },
  documents: {
    key: 'documents',
    path: '/documents',
    icon: IconFileText,
    labelKey: 'nav.documents',
    privileges: [PRIV.FLEET_DOCUMENT_MANAGE, PRIV.TRIP_CMR_MANAGE],
  },
  finance: {
    key: 'finance',
    path: '/finance',
    icon: IconFileInvoice,
    labelKey: 'nav.finance',
    privileges: [PRIV.FINANCE_INVOICE_VIEW],
  },
  fuel: { key: 'fuel', path: '/fuel', icon: IconGasStation, labelKey: 'nav.fuel', privileges: [PRIV.FUEL_TRANSACTION_VIEW] },
  reports: { key: 'reports', path: '/reports', icon: IconChartBar, labelKey: 'nav.reports', privileges: [PRIV.REPORT_VIEW] },
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

/** Menu items follow the effective privileges the backend sends with the user (role defaults + custom + overrides). */
export function canSee(item: NavItem, user: User): boolean {
  return hasAnyPrivilege(user, item.privileges);
}

export function visibleGroups(user: User): NavGroup[] {
  return NAV_GROUPS.map((g) => ({ ...g, items: g.items.filter((i) => canSee(i, user)) })).filter(
    (g) => g.items.length > 0,
  );
}

export function visibleScreens(user: User): NavItem[] {
  return visibleGroups(user).flatMap((g) => g.items);
}

export function startScreenPath(user: User): string {
  const preferred = user.startScreen;
  const key = preferred && canSee(SCREENS[preferred], user) ? preferred : DEFAULT_START_SCREEN[user.role];
  const item = SCREENS[key];
  return canSee(item, user) ? item.path : SCREENS.overview.path;
}
