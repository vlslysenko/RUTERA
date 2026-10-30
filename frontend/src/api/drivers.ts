import type { VehicleRef } from './fleet';
import { api } from './client';

/*
 * Drivers (DB_SCHEMA.md, group 3: driver, driver_card, driver_document); driver.driver/view for reads,
 * driver.driver/manage for every change (documents included).
 *   GET    /app/drivers                          -> Driver[] (own firma, deleted excluded, sorted by lastName, firstName)
 *   GET    /app/drivers/{id}                     -> Driver | 404
 *   POST   /app/drivers                          -> 201 Driver | 422 details per field (cardNumber: driver.card.number_taken)
 *   PUT    /app/drivers/{id}                     -> Driver (full replacement; an empty cardNumber removes the card)
 *   DELETE /app/drivers/{id}                     -> 204 (soft delete; vehicles lose him as default driver)
 *   GET    /app/drivers/{id}/documents           -> DriverDocument[] (validTo asc, nulls last)
 *   POST   /app/drivers/{id}/documents           -> 201 DriverDocument
 *   PUT    /app/drivers/{id}/documents/{docId}   -> DriverDocument;  DELETE -> 204
 * The driver-vehicle link lives on the vehicle (Vehicle.defaultDriver, chosen in the vehicle form);
 * Driver.defaultVehicle is derived from it (the first such vehicle by plate).
 */

export const DRIVER_STATUSES = ['ACTIVE', 'INACTIVE'] as const;
export type DriverStatus = (typeof DRIVER_STATUSES)[number];

export const DRIVER_DOCUMENT_KINDS = [
  'LICENSE',
  'CPC_CODE95',
  'MEDICAL',
  'PSYCHO',
  'ADR',
  'PASSPORT',
  'VISA',
  'WORK_PERMIT',
  'OTHER',
] as const;
export type DriverDocumentKind = (typeof DRIVER_DOCUMENT_KINDS)[number];

/** The current tachograph card; a new number replaces it (the history of cards comes later). */
export interface DriverCard {
  cardNumber: string;
  /** ISO 3166 alpha-2 */
  issuingCountry: string;
  /** YYYY-MM-DD */
  validTo: string | null;
  /** Written by the DDD import, read-only here; a new card number resets it. */
  lastDownloadAt: string | null;
}

export interface DriverDocument {
  id: number;
  kind: DriverDocumentKind;
  number: string | null;
  /** YYYY-MM-DD */
  validFrom: string | null;
  /** YYYY-MM-DD */
  validTo: string | null;
  remindDays: number;
}

export type DriverDocumentInput = Omit<DriverDocument, 'id' | 'remindDays'> & { remindDays: number | null };

/** Nearest deadline across documents and the card; the card has kind DRIVER_CARD. */
export interface DriverDeadline {
  kind: DriverDocumentKind | 'DRIVER_CARD';
  validTo: string;
}

export interface Driver {
  id: number;
  firstName: string;
  lastName: string;
  /** "First Last" */
  name: string;
  birthDate: string | null;
  phone: string | null;
  email: string | null;
  /** ISO 3166 alpha-2 */
  country: string | null;
  employmentFrom: string | null;
  employmentTo: string | null;
  status: DriverStatus;
  notes: string | null;
  /** Sign-in to the driver app, when the driver has one (not editable here yet). */
  user?: { id: number; login: string } | null;
  defaultVehicle: VehicleRef | null;
  card: DriverCard | null;
  nextDocument: DriverDeadline | null;
  createdAt: string;
  updatedAt: string;
}

/** Body of POST and PUT (full replacement); documents have their own endpoints. */
export interface DriverInput {
  firstName: string;
  lastName: string;
  birthDate: string | null;
  phone: string | null;
  email: string | null;
  country: string | null;
  employmentFrom: string | null;
  employmentTo: string | null;
  status: DriverStatus;
  notes: string | null;
  /** Empty removes the card; spaces are stripped and letters uppercased by the backend. */
  cardNumber: string | null;
  /** Required together with cardNumber. */
  cardIssuingCountry: string | null;
  cardValidTo: string | null;
}

export const driversApi = {
  list: () => api<Driver[]>('/drivers'),
  get: (id: number | string) => api<Driver>(`/drivers/${id}`),
  create: (body: DriverInput) => api<Driver>('/drivers', { method: 'POST', body }),
  update: (id: number | string, body: DriverInput) => api<Driver>(`/drivers/${id}`, { method: 'PUT', body }),
  remove: (id: number | string) => api<void>(`/drivers/${id}`, { method: 'DELETE' }),
  documents: (id: number | string) => api<DriverDocument[]>(`/drivers/${id}/documents`),
  createDocument: (id: number | string, body: DriverDocumentInput) =>
    api<DriverDocument>(`/drivers/${id}/documents`, { method: 'POST', body }),
  updateDocument: (id: number | string, docId: number, body: DriverDocumentInput) =>
    api<DriverDocument>(`/drivers/${id}/documents/${docId}`, { method: 'PUT', body }),
  removeDocument: (id: number | string, docId: number) =>
    api<void>(`/drivers/${id}/documents/${docId}`, { method: 'DELETE' }),
};

export const DRIVERS_KEY = ['drivers'] as const;
