import type { User } from '@/app/types';

/** Privilege keys used by the client (subset of com.rutera.common.security.Privileges). */
export const PRIV = {
  ADMIN_USER_VIEW: 'admin.user/view',
  ADMIN_USER_MANAGE: 'admin.user/manage',
  FLEET_VEHICLE_VIEW: 'fleet.vehicle/view',
  FLEET_DOCUMENT_MANAGE: 'fleet.document/manage',
  MAP_POSITION_VIEW: 'map.position/view',
  DRIVER_VIEW: 'driver.driver/view',
  TACHO_HOS_VIEW: 'tacho.hos/view',
  TRIP_TRIP_VIEW: 'trip.trip/view',
  TRIP_CMR_MANAGE: 'trip.cmr/manage',
  FINANCE_INVOICE_VIEW: 'finance.invoice/view',
  FUEL_TRANSACTION_VIEW: 'fuel.transaction/view',
  REPORT_VIEW: 'report.report/view',
  PROFILE_SELF_MANAGE: 'profile.self/manage',
} as const;

export type PrivilegeKey = (typeof PRIV)[keyof typeof PRIV];

/** The superuser has no explicit keys and bypasses every check (same rule as the backend). */
export function hasPrivilege(user: User, key: string): boolean {
  return user.role === 'SUPERUSER' || user.privileges.includes(key);
}

/** True when no keys are required or the user holds at least one of them. */
export function hasAnyPrivilege(user: User, keys: readonly string[] | undefined): boolean {
  return !keys || keys.length === 0 || keys.some((k) => hasPrivilege(user, k));
}
