import type { Firma, Role } from '@/app/types';
import type { LanguageCode } from '@/i18n/languages';
import type { Vehicle } from '@/api/fleet';

/* Test data for the in-browser mock API (dev only). Credentials are test values for this mock. */

export interface MockUser {
  id: number;
  login: string;
  password: string;
  name: string;
  role: Role;
  language: LanguageCode;
}

export const MOCK_FIRMA: Firma = { id: 1, name: 'TransPol Sp. z o.o.' };

export const MOCK_USERS: MockUser[] = [
  { id: 1, login: 'owner', password: 'owner', name: 'Anna Kowalska', role: 'OWNER', language: 'pl' },
  { id: 2, login: 'dispatcher', password: 'dispatcher', name: 'Piotr Nowak', role: 'DISPATCHER', language: 'pl' },
  { id: 3, login: 'logistician', password: 'logistician', name: 'Oleh Shevchenko', role: 'LOGISTICIAN', language: 'uk' },
  { id: 4, login: 'accountant', password: 'accountant', name: 'Ewa Zielinska', role: 'ACCOUNTANT', language: 'pl' },
];

export const MOCK_VEHICLES: Vehicle[] = [
  { id: 1, plate: 'DW 4471F', model: 'MAN TGX 18.470', status: 'ON_ROUTE', driver: 'Kowalski Jan', odometerKm: 412_380 },
  { id: 2, plate: 'DW 8821K', model: 'DAF XF 480', status: 'ON_ROUTE', driver: 'Nowak Piotr', odometerKm: 287_915 },
  { id: 3, plate: 'DW 2210P', model: 'Volvo FH 460', status: 'REST', driver: 'Shevchenko Oleh', odometerKm: 356_204 },
  { id: 4, plate: 'DW 5120C', model: 'Scania R 450', status: 'BASE', driver: 'Wisniewski Marek', odometerKm: 198_770 },
  { id: 5, plate: 'DW 3390H', model: 'Mercedes-Benz Actros 1845', status: 'SERVICE', driver: null, odometerKm: 521_043 },
];
