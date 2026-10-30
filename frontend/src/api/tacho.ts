import type { Driver } from './drivers';
import type { Vehicle, VehicleRef } from './fleet';
import { api, apiDownload } from './client';

/*
 * Tachograph file import (docs/TACHO_IMPORT_API.md v0.2); privileges tacho.file/upload and tacho.hos/view.
 *   POST   /app/tacho/files                 multipart "files" (1..50) -> TachoImportResult[] in the order of the files
 *   GET    /app/tacho/files?kind&driverId&vehicleId&unassigned&from&to&page&size -> {items, total}, newest first
 *   GET    /app/tacho/files/{id}            -> TachoFileDetail
 *   GET    /app/tacho/files/{id}/content    -> original bytes (upload)
 *   PUT    /app/tacho/files/{id}/assignment {driverId} | {vehicleId} -> TachoFile (match MANUAL)
 *   POST   /app/tacho/files/{id}/driver     -> 201 {driver, file}  (also driver.driver/manage)
 *   POST   /app/tacho/files/{id}/vehicle    -> 201 {vehicle, file} (also fleet.vehicle/manage)
 *   DELETE /app/tacho/files/{id}            -> 204, any file; everything read from it goes too
 */

export const TACHO_KINDS = ['DRIVER_CARD', 'VEHICLE_UNIT'] as const;
export type TachoKind = (typeof TACHO_KINDS)[number];

export type TachoGeneration = 'GEN1' | 'GEN2' | 'GEN2V2';
export type TachoMatch = 'CARD_NUMBER' | 'DRIVER_IDENTIFICATION' | 'VIN' | 'PLATE' | 'MANUAL' | 'NONE';
export type TachoImportStatus = 'IMPORTED' | 'DUPLICATE' | 'REJECTED';

/** Limits of one upload request (the backend checks them too). */
export const TACHO_MAX_FILES = 50;
export const TACHO_MAX_BYTES = 20 * 1024 * 1024;

/** How often files must be downloaded: driver cards every 28 days, vehicle units every 90 days. */
export const CARD_DOWNLOAD_DAYS = 28;
export const UNIT_DOWNLOAD_DAYS = 90;

export interface TachoFile {
  id: number;
  kind: TachoKind;
  generation: TachoGeneration;
  originalName: string;
  sizeBytes: number;
  sha256: string;
  cardNumber: string | null;
  cardIssuingCountry: string | null;
  cardValidTo: string | null;
  vin: string | null;
  plate: string | null;
  plateCountry: string | null;
  periodFrom: string | null;
  periodTo: string | null;
  downloadedAt: string | null;
  /** > 0: the file was read only partly, details in TachoFileDetail.warnings. */
  warningsCount: number;
  driver: { id: number; name: string } | null;
  vehicle: VehicleRef | null;
  match: TachoMatch;
  uploadedBy: { id: number; name: string };
  uploadedAt: string;
}

export interface TachoCardSuggestion {
  firstName: string;
  lastName: string;
  birthDate: string | null;
  cardNumber: string;
  cardIssuingCountry: string;
  cardValidTo: string | null;
}

export interface TachoUnitSuggestion {
  plate: string | null;
  plateCountry: string | null;
  vin: string | null;
  tachoGen: 'GEN1' | 'GEN2';
}

export interface TachoFileDetail extends TachoFile {
  /** Technical text for support, not translated. */
  warnings: string[];
  counts: {
    activityDays: number;
    vehiclesUsed: number;
    places: number;
    events: number;
    faults: number;
    cardInsertions: number;
    calibrations: number;
  };
  /** Only when match = NONE: data for "create from file". */
  suggestion: TachoCardSuggestion | TachoUnitSuggestion | null;
  /** Vehicle unit: who drove it. */
  drivers: { cardNumber: string; name: string; driverId: number | null; firstInsertion: string | null; lastWithdrawal: string | null }[];
}

export interface TachoImportResult {
  fileName: string;
  status: TachoImportStatus;
  /** DUPLICATE: the file uploaded earlier with the same sha256. */
  file: TachoFile | null;
  error: { code: string; message?: string } | null;
}

export interface TachoFileFilter {
  kind?: TachoKind;
  driverId?: number;
  vehicleId?: number;
  unassigned?: boolean;
  /** YYYY-MM-DD, upload date */
  from?: string;
  to?: string;
  page?: number;
  size?: number;
}

export interface TachoFilePage {
  items: TachoFile[];
  total: number;
}

export const tachoApi = {
  upload: (files: File[]) => {
    const body = new FormData();
    for (const f of files) body.append('files', f, f.name);
    return api<TachoImportResult[]>('/tacho/files', { method: 'POST', body });
  },
  list: (filter: TachoFileFilter) => api<TachoFilePage>('/tacho/files', { query: { ...filter } }),
  get: (id: number | string) => api<TachoFileDetail>(`/tacho/files/${id}`),
  download: (id: number | string) => apiDownload(`/tacho/files/${id}/content`),
  assign: (id: number | string, target: { driverId: number } | { vehicleId: number }) =>
    api<TachoFile>(`/tacho/files/${id}/assignment`, { method: 'PUT', body: target }),
  createDriver: (id: number | string) =>
    api<{ driver: Driver; file: TachoFile }>(`/tacho/files/${id}/driver`, { method: 'POST' }),
  createVehicle: (id: number | string) =>
    api<{ vehicle: Vehicle; file: TachoFile }>(`/tacho/files/${id}/vehicle`, { method: 'POST' }),
  remove: (id: number | string) => api<void>(`/tacho/files/${id}`, { method: 'DELETE' }),
};

export const TACHO_FILES_KEY = ['tacho', 'files'] as const;

export function isCardSuggestion(s: TachoFileDetail['suggestion']): s is TachoCardSuggestion {
  return s != null && 'cardNumber' in s;
}
