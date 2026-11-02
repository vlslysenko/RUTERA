import type { User } from '@/app/types';

/** Privilege keys used by the client (subset of com.rutera.common.security.Privileges). */
export const PRIV = {
  ADMIN_USER_VIEW: 'admin.user/view',
  ADMIN_USER_MANAGE: 'admin.user/manage',
  FLEET_VEHICLE_VIEW: 'fleet.vehicle/view',
  FLEET_VEHICLE_MANAGE: 'fleet.vehicle/manage',
  FLEET_DOCUMENT_MANAGE: 'fleet.document/manage',
  MAP_POSITION_VIEW: 'map.position/view',
  DRIVER_VIEW: 'driver.driver/view',
  DRIVER_MANAGE: 'driver.driver/manage',
  DRIVER_ABSENCE_MANAGE: 'driver.absence/manage',
  DRIVER_PAYMENT_VIEW: 'driver.payment/view',
  DRIVER_PAYMENT_MANAGE: 'driver.payment/manage',
  TACHO_HOS_VIEW: 'tacho.hos/view',
  TACHO_FILE_UPLOAD: 'tacho.file/upload',
  TACHO_VIOLATION_ACK: 'tacho.violation/ack',
  TRIP_TRIP_VIEW: 'trip.trip/view',
  TRIP_TRIP_MANAGE: 'trip.trip/manage',
  TRIP_ORDER_VIEW: 'trip.order/view',
  TRIP_ORDER_MANAGE: 'trip.order/manage',
  TRIP_CONTRACTOR_MANAGE: 'trip.contractor/manage',
  TRIP_CMR_MANAGE: 'trip.cmr/manage',
  FINANCE_INVOICE_VIEW: 'finance.invoice/view',
  FINANCE_INVOICE_MANAGE: 'finance.invoice/manage',
  FINANCE_PAYMENT_MANAGE: 'finance.payment/manage',
  FINANCE_REMINDER_MANAGE: 'finance.reminder/manage',
  FUEL_TRANSACTION_VIEW: 'fuel.transaction/view',
  FUEL_IMPORT_MANAGE: 'fuel.import/manage',
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
