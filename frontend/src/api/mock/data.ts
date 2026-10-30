import type { Vehicle } from '@/api/fleet';
import type { Firma, Role } from '@/app/types';
import type { LanguageCode } from '@/i18n/languages';

/* Test data for the in-browser mock API (dev only). Credentials are test values for this mock. */

export interface MockUser {
  id: number;
  login: string;
  password: string;
  firstName: string;
  lastName: string;
  role: Role;
  language: LanguageCode;
  phone: string | null;
  active: boolean;
  locked: boolean;
  failedLogins: number;
  deleted: boolean;
  createdAt: string;
  updatedAt: string;
}

export const MOCK_FIRMA: Firma = { id: 1, name: 'TransPol Sp. z o.o.' };

const T0 = '2026-10-01T08:00:00Z';

export const MOCK_USERS: MockUser[] = [
  { id: 1, login: 'owner', password: 'owner', firstName: 'Anna', lastName: 'Kowalska', role: 'OWNER', language: 'pl', phone: '+48 600 100 100', active: true, locked: false, failedLogins: 0, deleted: false, createdAt: T0, updatedAt: T0 },
  { id: 2, login: 'dispatcher', password: 'dispatcher', firstName: 'Piotr', lastName: 'Nowak', role: 'DISPATCHER', language: 'pl', phone: '+48 600 100 200', active: true, locked: false, failedLogins: 0, deleted: false, createdAt: T0, updatedAt: T0 },
  { id: 3, login: 'logistician', password: 'logistician', firstName: 'Oleh', lastName: 'Shevchenko', role: 'LOGISTICIAN', language: 'uk', phone: null, active: true, locked: false, failedLogins: 2, deleted: false, createdAt: T0, updatedAt: T0 },
  { id: 4, login: 'accountant', password: 'accountant', firstName: 'Ewa', lastName: 'Zielinska', role: 'ACCOUNTANT', language: 'pl', phone: null, active: true, locked: true, failedLogins: 0, deleted: false, createdAt: T0, updatedAt: T0 },
  { id: 5, login: 'driver', password: 'driver', firstName: 'Jan', lastName: 'Kowalski', role: 'DRIVER', language: 'pl', phone: '+48 600 100 500', active: false, locked: false, failedLogins: 0, deleted: false, createdAt: T0, updatedAt: T0 },
];

/** Role defaults, mirrored from com.rutera.common.security.RolePrivileges. */
const ALL_PRIVILEGES = [
  'admin.user/view', 'admin.user/manage', 'admin.role/manage', 'admin.firma/manage', 'admin.audit/view', 'admin.api_token/manage',
  'fleet.vehicle/view', 'fleet.vehicle/manage', 'fleet.document/manage',
  'map.position/view',
  'driver.driver/view', 'driver.driver/manage', 'driver.absence/manage', 'driver.payment/view', 'driver.payment/manage',
  'tacho.file/upload', 'tacho.hos/view', 'tacho.violation/ack',
  'trip.order/view', 'trip.order/manage', 'trip.trip/view', 'trip.trip/manage', 'trip.contractor/manage', 'trip.cmr/upload', 'trip.cmr/manage',
  'finance.invoice/view', 'finance.invoice/manage', 'finance.payment/manage', 'finance.reminder/manage',
  'fuel.transaction/view', 'fuel.import/manage',
  'report.report/view',
  'driver_app.self/use',
  'profile.self/manage',
];

export const ROLE_PRIVILEGES: Record<Role, string[]> = {
  SUPERUSER: [],
  OWNER: ALL_PRIVILEGES,
  DISPATCHER: [
    'fleet.vehicle/view', 'fleet.vehicle/manage', 'fleet.document/manage', 'map.position/view',
    'driver.driver/view', 'driver.driver/manage', 'driver.absence/manage',
    'tacho.file/upload', 'tacho.hos/view', 'tacho.violation/ack',
    'trip.order/view', 'trip.order/manage', 'trip.trip/view', 'trip.trip/manage', 'trip.contractor/manage', 'trip.cmr/upload', 'trip.cmr/manage',
    'fuel.transaction/view', 'report.report/view', 'profile.self/manage',
  ],
  LOGISTICIAN: [
    'fleet.vehicle/view', 'map.position/view', 'driver.driver/view', 'tacho.hos/view',
    'trip.order/view', 'trip.order/manage', 'trip.trip/view', 'trip.trip/manage', 'trip.contractor/manage', 'trip.cmr/upload', 'trip.cmr/manage',
    'fuel.transaction/view', 'report.report/view', 'profile.self/manage',
  ],
  ACCOUNTANT: [
    'fleet.vehicle/view', 'driver.driver/view', 'driver.payment/view', 'driver.payment/manage',
    'trip.order/view', 'trip.trip/view', 'trip.contractor/manage', 'trip.cmr/manage',
    'finance.invoice/view', 'finance.invoice/manage', 'finance.payment/manage', 'finance.reminder/manage',
    'fuel.transaction/view', 'report.report/view', 'profile.self/manage',
  ],
  DRIVER: ['driver_app.self/use', 'profile.self/manage'],
  API: ['fleet.vehicle/view', 'map.position/view', 'driver.driver/view', 'tacho.hos/view', 'trip.order/view', 'trip.trip/view', 'finance.invoice/view', 'fuel.transaction/view', 'report.report/view'],
  CUSTOM: [],
};

export const MOCK_VEHICLES: Vehicle[] = [
  { id: 1, plate: 'DW 4471F', model: 'MAN TGX 18.470', status: 'ON_ROUTE', driver: 'Kowalski Jan', odometerKm: 412_380 },
  { id: 2, plate: 'DW 8821K', model: 'DAF XF 480', status: 'ON_ROUTE', driver: 'Nowak Piotr', odometerKm: 287_915 },
  { id: 3, plate: 'DW 2210P', model: 'Volvo FH 460', status: 'REST', driver: 'Shevchenko Oleh', odometerKm: 356_204 },
  { id: 4, plate: 'DW 5120C', model: 'Scania R 450', status: 'BASE', driver: 'Wisniewski Marek', odometerKm: 198_770 },
  { id: 5, plate: 'DW 3390H', model: 'Mercedes-Benz Actros 1845', status: 'SERVICE', driver: null, odometerKm: 521_043 },
];
