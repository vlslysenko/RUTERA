import { api } from './client';

/*
 * Fleet (DB_SCHEMA.md, group 2: vehicle, vehicle_document); privileges fleet.vehicle/view, fleet.vehicle/manage,
 * fleet.document/manage. Trailers are vehicles too (kind TRAILER / SEMI_TRAILER).
 *   GET    /app/fleet/vehicles                 -> Vehicle[] (own firma, deleted excluded, sorted by plate)
 *   GET    /app/fleet/vehicles/{id}            -> Vehicle | 404
 *   POST   /app/fleet/vehicles                 -> 201 Vehicle | 422 details per field (plate: fleet.vehicle.plate_taken)
 *   PUT    /app/fleet/vehicles/{id}            -> Vehicle (full replacement)
 *   DELETE /app/fleet/vehicles/{id}            -> 204 (soft delete, the plate becomes free)
 *   GET    /app/fleet/vehicles/{id}/documents  -> VehicleDocument[] ordered by validTo, nulls last
 *   POST   /app/fleet/vehicles/{id}/documents  -> 201 VehicleDocument (fleet.document/manage)
 *   PUT    /app/fleet/vehicles/{id}/documents/{docId} -> VehicleDocument (full replacement)
 *   DELETE /app/fleet/vehicles/{id}/documents/{docId} -> 204 (hard delete)
 */

export const VEHICLE_KINDS = ['TRACTOR', 'TRUCK', 'VAN', 'SEMI_TRAILER', 'TRAILER'] as const;
export type VehicleKind = (typeof VEHICLE_KINDS)[number];

export const VEHICLE_STATUSES = ['ACTIVE', 'SERVICE', 'INACTIVE'] as const;
export type VehicleStatus = (typeof VEHICLE_STATUSES)[number];

export const EURO_CLASSES = ['EURO_3', 'EURO_4', 'EURO_5', 'EURO_6'] as const;
export type EuroClass = (typeof EURO_CLASSES)[number];

export const FUEL_TYPES = ['DIESEL', 'HVO', 'LNG', 'CNG', 'PETROL', 'ELECTRIC'] as const;
export type FuelType = (typeof FUEL_TYPES)[number];

export const TACHO_GENS = ['NONE', 'GEN1', 'GEN2'] as const;
export type TachoGen = (typeof TACHO_GENS)[number];

export const VEHICLE_DOCUMENT_KINDS = [
  'REGISTRATION',
  'INSURANCE_OC',
  'INSURANCE_AC',
  'INSPECTION',
  'TACHO_CALIBRATION',
  'LICENSE_COPY',
  'TOLL_DEVICE',
  'OTHER',
] as const;
export type VehicleDocumentKind = (typeof VEHICLE_DOCUMENT_KINDS)[number];

export interface DriverRef {
  id: number;
  name: string;
}

export interface VehicleRef {
  id: number;
  plate: string;
}

/** The nearest document deadline of a vehicle, for the list. */
export interface VehicleDeadline {
  kind: VehicleDocumentKind;
  /** YYYY-MM-DD */
  validTo: string;
}

export interface Vehicle {
  id: number;
  kind: VehicleKind;
  plate: string;
  vin: string | null;
  make: string | null;
  model: string | null;
  euroClass: EuroClass | null;
  axles: number | null;
  grossWeightKg: number | null;
  heightMm: number | null;
  /** Average consumption, l/100 km. */
  avgL100km: number | null;
  fuelType: FuelType | null;
  tachoGen: TachoGen;
  odometerKm: number | null;
  status: VehicleStatus;
  defaultDriver: DriverRef | null;
  defaultTrailer: VehicleRef | null;
  lastVuDownloadAt: string | null;
  nextDocument: VehicleDeadline | null;
  createdAt: string;
  updatedAt: string;
}

/** Body of POST and PUT (full replacement). */
export interface VehicleInput {
  kind: VehicleKind;
  plate: string;
  vin: string | null;
  make: string | null;
  model: string | null;
  euroClass: EuroClass | null;
  axles: number | null;
  grossWeightKg: number | null;
  heightMm: number | null;
  avgL100km: number | null;
  fuelType: FuelType | null;
  tachoGen: TachoGen;
  odometerKm: number | null;
  status: VehicleStatus;
  defaultDriverId: number | null;
  defaultTrailerId: number | null;
}

export interface VehicleDocument {
  id: number;
  kind: VehicleDocumentKind;
  number: string | null;
  /** YYYY-MM-DD */
  validFrom: string | null;
  /** YYYY-MM-DD */
  validTo: string | null;
  cost: number | null;
  currency: string | null;
  /** How many days before validTo the document turns into "needs attention". */
  remindDays: number;
}

/** Body of POST and PUT of a document; currency is required together with cost, remindDays defaults to 30. */
export type VehicleDocumentInput = Omit<VehicleDocument, 'id' | 'remindDays'> & { remindDays: number | null };

/** Trailers have no engine: no tachograph, fuel, consumption, odometer, driver or trailer of their own. */
export function isTrailer(kind: VehicleKind): boolean {
  return kind === 'TRAILER' || kind === 'SEMI_TRAILER';
}

/** "MAN TGX 18.510", or null when neither make nor model is known. */
export function makeModel(v: Pick<Vehicle, 'make' | 'model'>): string | null {
  return [v.make, v.model].filter(Boolean).join(' ') || null;
}

export const fleetApi = {
  list: () => api<Vehicle[]>('/fleet/vehicles'),
  get: (id: number | string) => api<Vehicle>(`/fleet/vehicles/${id}`),
  create: (body: VehicleInput) => api<Vehicle>('/fleet/vehicles', { method: 'POST', body }),
  update: (id: number | string, body: VehicleInput) => api<Vehicle>(`/fleet/vehicles/${id}`, { method: 'PUT', body }),
  remove: (id: number | string) => api<void>(`/fleet/vehicles/${id}`, { method: 'DELETE' }),
  documents: (id: number | string) => api<VehicleDocument[]>(`/fleet/vehicles/${id}/documents`),
  createDocument: (id: number | string, body: VehicleDocumentInput) =>
    api<VehicleDocument>(`/fleet/vehicles/${id}/documents`, { method: 'POST', body }),
  updateDocument: (id: number | string, docId: number, body: VehicleDocumentInput) =>
    api<VehicleDocument>(`/fleet/vehicles/${id}/documents/${docId}`, { method: 'PUT', body }),
  removeDocument: (id: number | string, docId: number) =>
    api<void>(`/fleet/vehicles/${id}/documents/${docId}`, { method: 'DELETE' }),
};

export const VEHICLES_KEY = ['fleet', 'vehicles'] as const;
