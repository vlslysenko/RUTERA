import {
  isTrailer,
  VEHICLE_DOCUMENT_KINDS,
  VEHICLE_KINDS,
  VEHICLE_STATUSES,
  type Vehicle,
  type VehicleDocument,
  type VehicleDocumentInput,
  type VehicleInput,
} from '@/api/fleet';

/* Fleet test data for the in-browser mock API: the pilot's five tractors and three semi-trailers.
 * Document dates are relative to today so every deadline state (overdue, soon, fine) is always visible. */

export interface MockVehicle extends Omit<Vehicle, 'defaultTrailer' | 'nextDocument' | 'defaultDriver'> {
  defaultDriverId: number | null;
  defaultTrailerId: number | null;
  deleted: boolean;
  documents: VehicleDocument[];
}

/** Drivers module does not exist yet; names for the default-driver column. */
const DRIVERS: Record<number, string> = {
  1: 'Kowalski Jan',
  2: 'Nowak Piotr',
  3: 'Shevchenko Oleh',
  4: 'Wiśniewski Marek',
};

function inDays(days: number): string {
  const d = new Date();
  d.setDate(d.getDate() + days);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

function daysAgo(days: number): string {
  return new Date(Date.now() - days * 86_400_000).toISOString();
}

let docId = 100;
function doc(kind: VehicleDocument['kind'], validToInDays: number | null, extra: Partial<VehicleDocument> = {}): VehicleDocument {
  return {
    id: ++docId,
    kind,
    number: null,
    validFrom: null,
    validTo: validToInDays == null ? null : inDays(validToInDays),
    cost: null,
    currency: null,
    remindDays: 30,
    ...extra,
  };
}

const T0 = '2026-10-01T08:00:00Z';

const base = {
  vin: null,
  euroClass: null,
  axles: null,
  grossWeightKg: null,
  heightMm: null,
  avgL100km: null,
  fuelType: null,
  tachoGen: 'NONE',
  odometerKm: null,
  status: 'ACTIVE',
  defaultDriverId: null,
  defaultTrailerId: null,
  lastVuDownloadAt: null,
  deleted: false,
  documents: [],
  createdAt: T0,
  updatedAt: T0,
} as const satisfies Partial<MockVehicle>;

const tractor = { ...base, kind: 'TRACTOR', euroClass: 'EURO_6', axles: 2, grossWeightKg: 18_000, heightMm: 4_000, fuelType: 'DIESEL', tachoGen: 'GEN2' } as const;
const semi = { ...base, kind: 'SEMI_TRAILER', axles: 3, grossWeightKg: 39_000, heightMm: 4_000 } as const;

export const MOCK_VEHICLES: MockVehicle[] = [
  {
    ...tractor, id: 1, plate: 'DW 4471F', vin: 'WMA06XZZ7NP123456', make: 'MAN', model: 'TGX 18.510', avgL100km: 29.8,
    odometerKm: 412_380, defaultDriverId: 1, defaultTrailerId: 6, lastVuDownloadAt: daysAgo(16),
    documents: [
      doc('INSPECTION', 6, { number: 'BT/2025/0412' }),
      doc('INSURANCE_OC', 136, { number: 'PZU 908877123', cost: 9_850, currency: 'PLN' }),
      doc('TACHO_CALIBRATION', 294),
      doc('REGISTRATION', null, { number: 'DR/BAA 1234567' }),
    ],
  },
  {
    ...tractor, id: 2, plate: 'DW 8821K', vin: 'XLRTEH4300G987654', make: 'DAF', model: 'XF 480', avgL100km: 28.9,
    odometerKm: 287_915, defaultDriverId: 2, defaultTrailerId: 7, lastVuDownloadAt: daysAgo(40),
    documents: [doc('INSURANCE_OC', -3, { number: 'WARTA 55120' }), doc('INSPECTION', 200)],
  },
  {
    ...tractor, id: 3, plate: 'DW 2210P', vin: 'YV2RT40A5MB765432', make: 'Volvo', model: 'FH 460', avgL100km: 30.4,
    odometerKm: 356_204, defaultDriverId: 3, defaultTrailerId: 8, lastVuDownloadAt: daysAgo(5),
    documents: [doc('TACHO_CALIBRATION', 21), doc('INSPECTION', 160)],
  },
  {
    ...tractor, id: 4, plate: 'DW 5120C', vin: 'YS2R4X20005432109', make: 'Scania', model: 'R 450', avgL100km: 29.1,
    odometerKm: 198_770, tachoGen: 'GEN1', defaultDriverId: 4, lastVuDownloadAt: daysAgo(70),
    documents: [doc('INSPECTION', 95)],
  },
  {
    ...tractor, id: 5, plate: 'DW 3390H', vin: 'WDB9634031L246810', make: 'Mercedes-Benz', model: 'Actros 1845', euroClass: 'EURO_5',
    avgL100km: 31.2, odometerKm: 521_043, tachoGen: 'GEN1', status: 'SERVICE',
    documents: [doc('INSURANCE_AC', 48), doc('INSPECTION', 230)],
  },
  { ...semi, id: 6, plate: 'WPR 55K7', make: 'Schmitz Cargobull', model: 'S.KO Cool', documents: [doc('INSPECTION', 320)] },
  { ...semi, id: 7, plate: 'WPR 61C2', make: 'Krone', model: 'Profi Liner', documents: [doc('INSPECTION', 12)] },
  { ...semi, id: 8, plate: 'WPR 70A9', make: 'Wielton', model: 'NS 3', status: 'INACTIVE' },
];

/** Same as vehicle.plate_key on the backend: case, spaces and hyphens do not make a plate different. */
const plateKey = (plate: string) => plate.replace(/[\s-]+/g, '').toUpperCase();

export function toVehicle(v: MockVehicle, all: MockVehicle[]): Vehicle {
  const { defaultDriverId, defaultTrailerId, deleted: _deleted, documents, ...rest } = v;
  const trailer = defaultTrailerId == null ? undefined : all.find((x) => x.id === defaultTrailerId && !x.deleted);
  const next = documents
    .filter((d): d is VehicleDocument & { validTo: string } => d.validTo != null)
    .sort((a, b) => a.validTo.localeCompare(b.validTo))[0];
  return {
    ...rest,
    defaultDriver: defaultDriverId == null ? null : { id: defaultDriverId, name: DRIVERS[defaultDriverId] ?? `#${defaultDriverId}` },
    defaultTrailer: trailer ? { id: trailer.id, plate: trailer.plate } : null,
    nextDocument: next ? { kind: next.kind, validTo: next.validTo } : null,
  };
}

/** Same checks as the backend should make; returns per-field codes. */
export function validateVehicle(b: Partial<VehicleInput>, all: MockVehicle[], selfId: number | null): Record<string, string> {
  const details: Record<string, string> = {};
  const plate = String(b.plate ?? '').trim();
  if (!plate) details.plate = 'validation.required';
  else if (plate.length > 20) details.plate = 'validation.too_long';
  else if (!/^[\p{L}\p{N}][\p{L}\p{N} -]*$/u.test(plate)) details.plate = 'validation.invalid_format';
  else if (all.some((x) => !x.deleted && x.id !== selfId && plateKey(x.plate) === plateKey(plate))) {
    details.plate = 'fleet.vehicle.plate_taken';
  }
  if (!b.kind || !VEHICLE_KINDS.includes(b.kind)) details.kind = 'validation.invalid_value';
  if (!b.status || !VEHICLE_STATUSES.includes(b.status)) details.status = 'validation.invalid_value';
  if (b.vin && !/^[A-Z0-9]{1,17}$/.test(b.vin)) details.vin = 'validation.invalid_format';
  if (b.defaultTrailerId != null) {
    const trailer = all.find((x) => x.id === b.defaultTrailerId && !x.deleted);
    if (!trailer || !isTrailer(trailer.kind) || trailer.id === selfId) details.defaultTrailerId = 'validation.invalid_value';
  }
  return details;
}

export function validateDocument(b: Partial<VehicleDocumentInput>): Record<string, string> {
  const details: Record<string, string> = {};
  if (!b.kind || !VEHICLE_DOCUMENT_KINDS.includes(b.kind)) details.kind = 'validation.required';
  if (b.validFrom && b.validTo && b.validTo < b.validFrom) details.validTo = 'validation.invalid_value';
  if (b.currency && !/^[A-Za-z]{3}$/.test(b.currency)) details.currency = 'validation.invalid_format';
  else if (b.cost != null && !b.currency) details.currency = 'validation.required';
  if (b.remindDays != null && (b.remindDays < 0 || b.remindDays > 365)) details.remindDays = 'validation.invalid_value';
  return details;
}

export function toDocument(b: VehicleDocumentInput, id: number): VehicleDocument {
  return {
    id,
    kind: b.kind,
    number: b.number?.trim() || null,
    validFrom: b.validFrom || null,
    validTo: b.validTo || null,
    cost: b.cost,
    currency: b.cost == null ? null : (b.currency?.toUpperCase() ?? null),
    remindDays: b.remindDays ?? 30,
  };
}

export function applyVehicleInput(target: MockVehicle, b: VehicleInput): void {
  Object.assign(target, {
    kind: b.kind,
    plate: b.plate.trim().replace(/\s+/g, ' ').toUpperCase(),
    vin: b.vin,
    make: b.make,
    model: b.model,
    euroClass: b.euroClass,
    axles: b.axles,
    grossWeightKg: b.grossWeightKg,
    heightMm: b.heightMm,
    avgL100km: b.avgL100km,
    fuelType: b.fuelType,
    tachoGen: b.tachoGen,
    odometerKm: b.odometerKm,
    status: b.status,
    defaultDriverId: b.defaultDriverId,
    defaultTrailerId: b.defaultTrailerId,
    updatedAt: new Date().toISOString(),
  });
}
