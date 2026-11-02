import { api } from './client';

/*
 * Fuel cards and imports of provider exports (backend README "Fuel cards and imports of provider exports"): provider
 * presets of header synonyms, a preview with a column mapping and read options the user can correct (saved per firma
 * and provider), transactions matched to a vehicle, driver and trip, fuel cards with masked numbers.
 * Privileges: fuel.transaction/view reads; fuel.import/manage imports, undoes, edits cards, mappings and transactions.
 * Naive file times are read in the firma zone and returned as ISO instants; row errors use validation.* codes.
 */

export const FUEL_PROVIDERS = ['DKV', 'UTA', 'SHELL', 'BP', 'ORLEN', 'E100', 'AS24', 'OMV', 'OTHER'] as const;
export type FuelProvider = (typeof FUEL_PROVIDERS)[number];

/** Columns a file can have; required: date, product, gross (or net + VAT), card number or plate. */
export const MAPPING_FIELDS = [
  'cardNumber', 'plate', 'date', 'time', 'transactionId', 'station', 'country', 'product', 'quantity', 'unit', 'unitPrice', 'net', 'vat',
  'gross', 'currency', 'odometer',
] as const;
export type MappingField = (typeof MAPPING_FIELDS)[number];
export type FuelMapping = Partial<Record<MappingField, string | null>>;

export const FUEL_PRODUCTS = ['DIESEL', 'ADBLUE', 'PETROL', 'LNG', 'CNG', 'TOLL', 'SERVICE', 'OTHER'] as const;
export type FuelProduct = (typeof FUEL_PRODUCTS)[number];

export const FUEL_UNITS = ['L', 'KG', 'PCS'] as const;
export type FuelUnit = (typeof FUEL_UNITS)[number];

/** CARD: the card's vehicle; PLATE: the plate column; MANUAL: set by hand; NONE: no vehicle. */
export type FuelMatch = 'CARD' | 'PLATE' | 'MANUAL' | 'NONE';

export interface Ref {
  id: number;
  name: string;
}

/** How a file is read; empty fields mean automatic. */
export interface FuelReadOptions {
  /** Java date pattern, e.g. dd.MM.yyyy; overrides recognition. */
  dateFormat: string | null;
  /** true: a lone comma is the decimal separator; false: a lone point is; null: automatic. */
  decimalComma: boolean | null;
  /** Currency of rows without a currency column (else the firma's base currency). */
  currency: string | null;
}

/** One row read with the mapping; null when empty, numbers as numbers. */
export interface FuelPreviewValues {
  at: string | null;
  /** Masked. */
  cardNumber: string | null;
  plate: string | null;
  transactionId: string | null;
  station: string | null;
  country: string | null;
  /** Product group. */
  product: FuelProduct | null;
  productRaw: string | null;
  quantity: number | null;
  unit: FuelUnit | null;
  unitPrice: number | null;
  net: number | null;
  vat: number | null;
  gross: number | null;
  currency: string | null;
  odometer: number | null;
}

export interface FuelPreviewRow {
  /** Row in the file, 1-based. */
  row: number;
  values: FuelPreviewValues;
  /** validation.* code per mapping field that could not be read. */
  errors: Partial<Record<MappingField, string>>;
}

export interface FuelPreview {
  provider: FuelProvider;
  /** File row of the headers, 1-based. */
  headerRow: number;
  /** Headers in file order. */
  headers: string[];
  /** Every field, with its header or null. */
  mapping: FuelMapping;
  /** Required fields without a column. */
  missing: MappingField[];
  options: FuelReadOptions;
  /** Data rows in the file. */
  dataRows: number;
  /** The first 10 data rows read with the mapping and options. */
  rows: FuelPreviewRow[];
}

export interface FuelImport {
  id: number;
  provider: FuelProvider;
  fileName: string;
  periodFrom: string | null;
  periodTo: string | null;
  rowsTotal: number;
  rowsNew: number;
  rowsDup: number;
  rowsError: number;
  status: 'DONE';
  /** First 100. */
  errors: { row: number; field: string; code: string }[];
  mapping: FuelMapping;
  importedBy: Ref;
  createdAt: string;
}

export interface FuelTransaction {
  id: number;
  /** ISO instant */
  at: string;
  provider: FuelProvider | null;
  externalId: string | null;
  card: { id: number; masked: string } | null;
  /** name = plate */
  vehicle: Ref | null;
  driver: Ref | null;
  /** name = trip number */
  trip: Ref | null;
  station: string | null;
  country: string | null;
  product: FuelProduct;
  /** As written in the file. */
  productRaw: string | null;
  quantity: number | null;
  unit: FuelUnit | null;
  unitPrice: number | null;
  net: number | null;
  vat: number | null;
  gross: number;
  currency: string;
  odometerKm: number | null;
  /** Plate as written in the file. */
  plateRaw: string | null;
  match: FuelMatch;
  source: 'IMPORT' | 'MANUAL';
  importId: number | null;
  notes: string | null;
  createdAt: string;
}

/** Body of POST and PUT of a manual transaction (PUT of an imported one answers 409; use the assignment). */
export interface FuelTransactionInput {
  at: string;
  cardId: number | null;
  vehicleId: number | null;
  driverId: number | null;
  tripId: number | null;
  station: string | null;
  country: string | null;
  product: FuelProduct;
  quantity: number | null;
  unit: FuelUnit | null;
  unitPrice: number | null;
  net: number | null;
  vat: number | null;
  gross: number;
  currency: string;
  odometerKm: number | null;
  notes: string | null;
}

/** PATCH: replaces the assignment (match MANUAL, NONE when vehicle and driver are both empty). */
export interface FuelAssignment {
  vehicleId: number | null;
  driverId: number | null;
  tripId: number | null;
  notes: string | null;
}

export interface FuelFilter {
  from?: string;
  to?: string;
  vehicleId?: number;
  driverId?: number;
  cardId?: number;
  tripId?: number;
  importId?: number;
  product?: FuelProduct;
  country?: string;
  unmatched?: boolean;
  q?: string;
  page?: number;
  size?: number;
}

export interface FuelTotals {
  totals: { currency: string; net: number; vat: number; gross: number }[];
  quantities: { product: FuelProduct; unit: FuelUnit; quantity: number }[];
}

export interface FuelCard {
  id: number;
  provider: FuelProvider;
  /** "7034 **** 1234"; the full number is never stored nor returned. */
  cardNumberMasked: string;
  /** name = plate */
  vehicle: Ref | null;
  driver: Ref | null;
  validTo: string | null;
  active: boolean;
  notes: string | null;
  createdAt: string;
  updatedAt: string;
}

/** cardNumber is write-only: on PUT, null keeps the stored number (a new provider needs it again). */
export interface FuelCardInput {
  provider: FuelProvider;
  cardNumber: string | null;
  vehicleId: number | null;
  driverId: number | null;
  validTo: string | null;
  active: boolean;
  notes: string | null;
}

/** Multipart body: the file part plus plain fields; mapping and options as JSON strings. */
function importForm(file: File, provider: FuelProvider | null, mapping?: FuelMapping, options?: FuelReadOptions): FormData {
  const form = new FormData();
  form.append('file', file);
  if (provider) form.append('provider', provider);
  if (mapping) form.append('mapping', JSON.stringify(mapping));
  if (options) form.append('options', JSON.stringify(options));
  return form;
}

export const fuelApi = {
  /** Without a mapping the saved one of the firma and provider applies, else header recognition. */
  preview: (file: File, provider: FuelProvider | null, mapping?: FuelMapping, options?: FuelReadOptions) =>
    api<FuelPreview>('/fuel/imports/preview', { method: 'POST', body: importForm(file, provider, mapping, options) }),
  /** 422 file: fuel.import.columns_missing | unreadable | too_large (+ columns.<field> per missing field). */
  importFile: (file: File, provider: FuelProvider, mapping: FuelMapping, options: FuelReadOptions, saveMapping: boolean) => {
    const form = importForm(file, provider, mapping, options);
    form.append('saveMapping', String(saveMapping));
    return api<FuelImport>('/fuel/imports', { method: 'POST', body: form });
  },
  imports: (page = 0, size = 50) => api<{ items: FuelImport[]; total: number }>('/fuel/imports', { query: { page, size } }),
  importOf: (id: number | string) => api<FuelImport>(`/fuel/imports/${id}`),
  /** Removes the import and its transactions. */
  removeImport: (id: number | string) => api<void>(`/fuel/imports/${id}`, { method: 'DELETE' }),

  transactions: (filter: FuelFilter) =>
    api<{ items: FuelTransaction[]; total: number } & FuelTotals>('/fuel/transactions', { query: { ...filter } }),
  transaction: (id: number | string) => api<FuelTransaction>(`/fuel/transactions/${id}`),
  createTransaction: (body: FuelTransactionInput) => api<FuelTransaction>('/fuel/transactions', { method: 'POST', body }),
  updateTransaction: (id: number | string, body: FuelTransactionInput) => api<FuelTransaction>(`/fuel/transactions/${id}`, { method: 'PUT', body }),
  assign: (id: number | string, body: FuelAssignment) => api<FuelTransaction>(`/fuel/transactions/${id}`, { method: 'PATCH', body }),
  removeTransaction: (id: number | string) => api<void>(`/fuel/transactions/${id}`, { method: 'DELETE' }),

  cards: () => api<FuelCard[]>('/fuel/cards'),
  card: (id: number | string) => api<FuelCard>(`/fuel/cards/${id}`),
  createCard: (body: FuelCardInput) => api<FuelCard>('/fuel/cards', { method: 'POST', body }),
  updateCard: (id: number | string, body: FuelCardInput) => api<FuelCard>(`/fuel/cards/${id}`, { method: 'PUT', body }),
  removeCard: (id: number | string) => api<void>(`/fuel/cards/${id}`, { method: 'DELETE' }),
};

export const FUEL_KEY = ['fuel'] as const;
export const FUEL_TX_KEY = ['fuel', 'transactions'] as const;
export const FUEL_IMPORTS_KEY = ['fuel', 'imports'] as const;
export const FUEL_CARDS_KEY = ['fuel', 'cards'] as const;

/** Fields an import cannot do without (one of each group). */
export const REQUIRED_GROUPS: MappingField[][] = [['date'], ['product'], ['gross', 'net'], ['cardNumber', 'plate']];
