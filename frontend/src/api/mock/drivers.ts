import {
  DRIVER_DOCUMENT_KINDS,
  DRIVER_STATUSES,
  type Driver,
  type DriverCard,
  type DriverDeadline,
  type DriverDocument,
  type DriverDocumentInput,
  type DriverInput,
} from '@/api/drivers';
import type { VehicleRef } from '@/api/fleet';

/* Drivers test data for the in-browser mock API: the pilot's four drivers on the tractors plus one who left.
 * Dates are relative to today so every deadline state is visible. */

export interface MockDriver extends Omit<Driver, 'name' | 'defaultVehicle' | 'nextDocument' | 'card'> {
  card: DriverCard | null;
  deleted: boolean;
  documents: DriverDocument[];
}

function inDays(days: number): string {
  const d = new Date();
  d.setDate(d.getDate() + days);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

let docId = 500;
function doc(kind: DriverDocument['kind'], validToInDays: number | null, number: string | null = null): DriverDocument {
  return { id: ++docId, kind, number, validFrom: null, validTo: validToInDays == null ? null : inDays(validToInDays), remindDays: 30 };
}

const T0 = '2026-10-01T08:00:00Z';
const base = {
  birthDate: null,
  phone: null,
  email: null,
  country: 'PL',
  employmentFrom: null,
  employmentTo: null,
  status: 'ACTIVE',
  notes: null,
  user: null,
  card: null,
  deleted: false,
  documents: [],
  createdAt: T0,
  updatedAt: T0,
} as const satisfies Partial<MockDriver>;

export const MOCK_DRIVERS: MockDriver[] = [
  {
    ...base, id: 1, firstName: 'Jan', lastName: 'Kowalski', birthDate: '1979-04-12', phone: '+48 600 100 500',
    employmentFrom: '2019-03-01', user: { id: 5, login: 'driver' },
    card: { cardNumber: 'PL00000012345600', issuingCountry: 'PL', validTo: inDays(410), lastDownloadAt: new Date(Date.now() - 12 * 86_400_000).toISOString() },
    documents: [doc('LICENSE', 1200, 'PL/0412/19'), doc('CPC_CODE95', 25), doc('MEDICAL', 180), doc('ADR', -4, 'ADR 77231')],
  },
  {
    ...base, id: 2, firstName: 'Piotr', lastName: 'Nowak', birthDate: '1985-09-30', phone: '+48 600 100 600',
    employmentFrom: '2021-06-15',
    card: { cardNumber: 'PL00000098765400', issuingCountry: 'PL', validTo: inDays(18), lastDownloadAt: null },
    documents: [doc('LICENSE', 900), doc('CPC_CODE95', 400), doc('MEDICAL', 60)],
  },
  {
    ...base, id: 3, firstName: 'Oleh', lastName: 'Shevchenko', birthDate: '1990-01-21', phone: '+380 67 123 45 67',
    country: 'UA', employmentFrom: '2023-02-01', notes: 'Karta pobytu do odnowienia w marcu.',
    card: { cardNumber: 'PL00000055544400', issuingCountry: 'PL', validTo: inDays(1500), lastDownloadAt: null },
    documents: [doc('LICENSE', 2000), doc('CPC_CODE95', 700), doc('WORK_PERMIT', 140, 'WP-2025-1182'), doc('VISA', 140)],
  },
  {
    ...base, id: 4, firstName: 'Marek', lastName: 'Wiśniewski', birthDate: '1972-11-03', phone: '+48 600 100 800',
    employmentFrom: '2015-01-10',
    documents: [doc('LICENSE', 300), doc('MEDICAL', 9), doc('PSYCHO', 9)],
  },
  {
    ...base, id: 5, firstName: 'Tomasz', lastName: 'Zając', status: 'INACTIVE', employmentFrom: '2020-05-01',
    employmentTo: '2026-06-30',
  },
];

export function toDriver(d: MockDriver, vehicles: (VehicleRef & { defaultDriverId: number | null; deleted: boolean })[]): Driver {
  const { deleted: _deleted, documents, ...rest } = d;
  const vehicle = vehicles
    .filter((v) => !v.deleted && v.defaultDriverId === d.id)
    .sort((a, b) => a.plate.localeCompare(b.plate))[0];
  const deadlines: DriverDeadline[] = documents
    .filter((x): x is DriverDocument & { validTo: string } => x.validTo != null)
    .map((x) => ({ kind: x.kind, validTo: x.validTo }));
  if (d.card?.validTo) deadlines.push({ kind: 'DRIVER_CARD', validTo: d.card.validTo });
  deadlines.sort((a, b) => a.validTo.localeCompare(b.validTo));
  return {
    ...rest,
    name: `${d.firstName} ${d.lastName}`.trim(),
    defaultVehicle: vehicle ? { id: vehicle.id, plate: vehicle.plate } : null,
    nextDocument: deadlines[0] ?? null,
  };
}

const cardOf = (v: string | null | undefined) => (v ?? '').replace(/\s+/g, '').toUpperCase();

/** Same checks as the backend should make; returns per-field codes. */
export function validateDriver(b: Partial<DriverInput>, all: MockDriver[], selfId: number | null): Record<string, string> {
  const details: Record<string, string> = {};
  if (!b.firstName?.trim()) details.firstName = 'validation.required';
  if (!b.lastName?.trim()) details.lastName = 'validation.required';
  if (b.email && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(b.email)) details.email = 'validation.invalid_format';
  if (b.country && !/^[A-Za-z]{2}$/.test(b.country)) details.country = 'validation.invalid_format';
  if (b.employmentFrom && b.employmentTo && b.employmentTo < b.employmentFrom) details.employmentTo = 'validation.invalid_value';
  if (b.status && !DRIVER_STATUSES.includes(b.status)) details.status = 'validation.invalid_value';
  const card = cardOf(b.cardNumber);
  if (card) {
    if (!/^[A-Z0-9]{1,32}$/.test(card)) details.cardNumber = 'validation.invalid_format';
    else if (all.some((x) => !x.deleted && x.id !== selfId && x.card?.cardNumber === card)) {
      details.cardNumber = 'driver.card.number_taken';
    }
    if (!b.cardIssuingCountry) details.cardIssuingCountry = 'validation.required';
    else if (!/^[A-Za-z]{2}$/.test(b.cardIssuingCountry)) details.cardIssuingCountry = 'validation.invalid_format';
  }
  return details;
}

export function applyDriverInput(target: MockDriver, b: DriverInput): void {
  const card = cardOf(b.cardNumber);
  Object.assign(target, {
    firstName: b.firstName.trim(),
    lastName: b.lastName.trim(),
    birthDate: b.birthDate,
    phone: b.phone,
    email: b.email,
    country: b.country?.toUpperCase() ?? null,
    employmentFrom: b.employmentFrom,
    employmentTo: b.employmentTo,
    status: b.status ?? 'ACTIVE',
    notes: b.notes,
    card: card
      ? {
          cardNumber: card,
          issuingCountry: (b.cardIssuingCountry ?? '').toUpperCase(),
          validTo: b.cardValidTo,
          // A new card number starts without downloads.
          lastDownloadAt: target.card?.cardNumber === card ? target.card.lastDownloadAt : null,
        }
      : null,
    updatedAt: new Date().toISOString(),
  });
}

export function validateDriverDocument(b: Partial<DriverDocumentInput>): Record<string, string> {
  const details: Record<string, string> = {};
  if (!b.kind || !DRIVER_DOCUMENT_KINDS.includes(b.kind)) details.kind = 'validation.required';
  if (b.validFrom && b.validTo && b.validTo < b.validFrom) details.validTo = 'validation.invalid_value';
  if (b.remindDays != null && (b.remindDays < 0 || b.remindDays > 365)) details.remindDays = 'validation.invalid_value';
  return details;
}

export function toDriverDocument(b: DriverDocumentInput, id: number): DriverDocument {
  return {
    id,
    kind: b.kind,
    number: b.number?.trim() || null,
    validFrom: b.validFrom || null,
    validTo: b.validTo || null,
    remindDays: b.remindDays ?? 30,
  };
}
