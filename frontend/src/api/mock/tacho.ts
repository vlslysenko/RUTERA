import type {
  TachoCardSuggestion,
  TachoFile,
  TachoFileDetail,
  TachoGeneration,
  TachoKind,
  TachoUnitSuggestion,
} from '@/api/tacho';
import { TACHO_MAX_BYTES } from '@/api/tacho';

/* Tachograph files for the in-browser mock API. The mock cannot parse DDD: it hashes the real bytes (so duplicates
 * behave as on the backend) and derives the kind and the "contents" from the file name and the hash. */

export interface MockTachoFile extends TachoFileDetail {
  bytes: Blob | null;
}

/** Card number and name of a mock driver, or plate and VIN of a mock vehicle, that a file can belong to. */
export interface MockCardOwner {
  driverId: number;
  name: string;
  cardNumber: string;
  issuingCountry: string;
}

export interface MockUnitOwner {
  vehicleId: number;
  plate: string;
  vin: string | null;
}

const EXTENSIONS = ['ddd', 'esm', 'tgd', 'c1b', 'v1b', 'dtc'];

export async function sha256Hex(blob: Blob): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', await blob.arrayBuffer());
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('');
}

/** Same checks the backend makes before parsing; null when the file looks like a tachograph download. */
export function rejectReason(file: File): string | null {
  if (file.size > TACHO_MAX_BYTES) return 'tacho.file.too_large';
  const ext = file.name.split('.').pop()?.toLowerCase() ?? '';
  if (!EXTENSIONS.includes(ext)) return 'tacho.file.not_tachograph';
  if (file.size < 16) return 'tacho.file.truncated';
  if (/workshop|warsztat|company|firma|control/i.test(file.name)) return 'tacho.file.unsupported';
  return null;
}

function guessKind(name: string, hash: string): TachoKind {
  const n = name.toLowerCase();
  if (/^c_|card|karta|\.c1b$/.test(n)) return 'DRIVER_CARD';
  if (/^m_|^v_|vu|\.v1b$/.test(n)) return 'VEHICLE_UNIT';
  return parseInt(hash.slice(0, 2), 16) % 2 === 0 ? 'DRIVER_CARD' : 'VEHICLE_UNIT';
}

function isoDaysAgo(days: number, base = Date.now()): string {
  return new Date(base - days * 86_400_000).toISOString();
}

function dateDaysAgo(days: number): string {
  return isoDaysAgo(days).slice(0, 10);
}

/** Builds a parsed-looking file; owner = undefined means nothing in the firma matches (match NONE). */
export function buildFile(
  id: number,
  name: string,
  size: number,
  hash: string,
  uploadedBy: { id: number; name: string },
  cardOwners: MockCardOwner[],
  unitOwners: MockUnitOwner[],
): MockTachoFile {
  const kind = guessKind(name, hash);
  const n = (i: number) => parseInt(hash.slice(i * 2, i * 2 + 2), 16);
  const generation: TachoGeneration = (['GEN1', 'GEN2', 'GEN2V2'] as const)[n(1) % 3];
  const downloadedDaysAgo = n(2) % 20;
  const periodDays = kind === 'DRIVER_CARD' ? 28 : 90;
  const base: MockTachoFile = {
    id,
    kind,
    generation,
    originalName: name,
    sizeBytes: size,
    sha256: hash,
    cardNumber: null,
    cardIssuingCountry: null,
    cardValidTo: null,
    vin: null,
    plate: null,
    plateCountry: null,
    periodFrom: dateDaysAgo(downloadedDaysAgo + periodDays),
    periodTo: dateDaysAgo(downloadedDaysAgo),
    downloadedAt: isoDaysAgo(downloadedDaysAgo),
    warningsCount: n(3) % 7 === 0 ? 2 : 0,
    driver: null,
    vehicle: null,
    match: 'NONE',
    uploadedBy,
    uploadedAt: new Date().toISOString(),
    warnings: [],
    counts: {
      activityDays: kind === 'DRIVER_CARD' ? 20 + (n(4) % 9) : 60 + (n(4) % 30),
      vehiclesUsed: kind === 'DRIVER_CARD' ? 1 + (n(5) % 3) : 0,
      places: 10 + (n(6) % 30),
      events: n(7) % 6,
      faults: n(8) % 3,
      cardInsertions: kind === 'DRIVER_CARD' ? 0 : 15 + (n(9) % 40),
      calibrations: kind === 'DRIVER_CARD' ? 0 : 1,
    },
    suggestion: null,
    drivers: [],
    bytes: null,
  };
  if (base.warningsCount > 0) {
    base.warnings = ['EF Places: record 17 has an invalid timestamp, skipped', 'Signature of EF Events_Data not verified'];
  }

  if (kind === 'DRIVER_CARD') {
    const owner = cardOwners[n(10) % (cardOwners.length + 1)];
    if (owner) {
      Object.assign(base, {
        cardNumber: owner.cardNumber,
        cardIssuingCountry: owner.issuingCountry,
        cardValidTo: dateDaysAgo(-700),
        driver: { id: owner.driverId, name: owner.name },
        match: 'CARD_NUMBER',
      });
    } else {
      const suggestion: TachoCardSuggestion = {
        firstName: 'Andrzej',
        lastName: 'Nowicki',
        birthDate: '1983-05-17',
        cardNumber: `PL0000077${hash.slice(0, 7).toUpperCase().replace(/[^0-9]/g, '7').padEnd(7, '7')}`,
        cardIssuingCountry: 'PL',
        cardValidTo: dateDaysAgo(-900),
      };
      Object.assign(base, {
        cardNumber: suggestion.cardNumber,
        cardIssuingCountry: suggestion.cardIssuingCountry,
        cardValidTo: suggestion.cardValidTo,
        suggestion,
      });
    }
  } else {
    const owner = unitOwners[n(10) % (unitOwners.length + 1)];
    base.drivers = cardOwners.slice(0, 2).map((c, i) => ({
      cardNumber: c.cardNumber,
      name: c.name,
      driverId: c.driverId,
      firstInsertion: isoDaysAgo(downloadedDaysAgo + 60 - i * 20),
      lastWithdrawal: isoDaysAgo(downloadedDaysAgo + 1 + i),
    }));
    if (owner) {
      Object.assign(base, {
        vin: owner.vin,
        plate: owner.plate,
        plateCountry: 'PL',
        vehicle: { id: owner.vehicleId, plate: owner.plate },
        match: owner.vin ? 'VIN' : 'PLATE',
      });
    } else {
      const suggestion: TachoUnitSuggestion = {
        plate: 'WGM 7Q21',
        plateCountry: 'PL',
        vin: `WDB96340310${hash.slice(0, 6).toUpperCase().replace(/[^0-9A-HJ-NPR-Z]/g, '0')}`,
        tachoGen: generation === 'GEN1' ? 'GEN1' : 'GEN2',
      };
      Object.assign(base, { vin: suggestion.vin, plate: suggestion.plate, plateCountry: suggestion.plateCountry, suggestion });
    }
  }
  return base;
}

/** List row: the detail without the parts only the card needs. */
export function toTachoFile(f: MockTachoFile): TachoFile {
  const { warnings: _w, counts: _c, suggestion: _s, drivers: _d, bytes: _b, ...file } = f;
  return file;
}

export function toTachoDetail(f: MockTachoFile): TachoFileDetail {
  const { bytes: _b, ...detail } = f;
  return { ...detail, suggestion: f.match === 'NONE' ? f.suggestion : null };
}
