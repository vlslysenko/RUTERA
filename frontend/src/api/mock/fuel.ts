import type {
  FuelAssignment,
  FuelCard,
  FuelCardInput,
  FuelImport,
  FuelMapping,
  FuelPreview,
  FuelPreviewRow,
  FuelPreviewValues,
  FuelProduct,
  FuelProvider,
  FuelReadOptions,
  FuelTransaction,
  FuelTransactionInput,
  MappingField,
} from '@/api/fuel';
import { FUEL_PROVIDERS, MAPPING_FIELDS } from '@/api/fuel';
import { tripAtForFuel, tripForInvoice } from './trips';

/*
 * Fuel card import for the in-browser mock API: CSV only (an XLSX answers file: fuel.import.unreadable here), header
 * synonyms per field, mapping saved per provider, duplicates by (provider, externalId), matching vehicle by card then
 * plate, driver by card then the main driver of the vehicle's trip at that time.
 */

export interface MockReply {
  status: number;
  body?: unknown;
}

export interface FuelLookup {
  vehicleByPlate(plate: string): { id: number; name: string } | undefined;
  vehicle(id: number): { id: number; name: string } | undefined;
  driverName(id: number): string | null;
  me: { id: number; name: string };
}

const fail = (status: number, code: string, details?: Record<string, string>): MockReply => ({ status, body: details ? { code, details } : { code } });
const invalid = (details: Record<string, string>) => fail(422, 'error.validation', details);
const notFound = () => fail(404, 'error.not_found');
const blank = (s: string | null | undefined) => (s ?? '').trim() || null;
const r2 = (v: number) => Math.round((v + Number.EPSILON) * 100) / 100;
const MAX_ROWS = 50_000;
const MAX_BYTES = 20 * 1024 * 1024;

/** Header synonyms (lower case) of the presets; OTHER uses all of them. */
const SYNONYMS: Record<MappingField, string[]> = {
  cardNumber: ['card', 'card number', 'card no', 'karta', 'nr karty', 'numer karty', 'kartennummer', 'karte', 'pan'],
  plate: ['plate', 'registration', 'licence plate', 'nr rej', 'nr rejestracyjny', 'numer rejestracyjny', 'kennzeichen', 'pojazd', 'vehicle'],
  date: ['date', 'data', 'datum', 'transaction date', 'data transakcji', 'belegdatum'],
  time: ['time', 'czas', 'godzina', 'zeit', 'uhrzeit'],
  transactionId: ['transaction id', 'transaction', 'receipt', 'nr transakcji', 'beleg-nr', 'belegnummer', 'id'],
  station: ['station', 'stacja', 'tankstelle', 'location', 'site'],
  country: ['country', 'kraj', 'land', 'country code'],
  product: ['product', 'produkt', 'towar', 'article', 'artikel'],
  quantity: ['quantity', 'ilość', 'ilosc', 'menge', 'volume', 'litres', 'liters'],
  unit: ['unit', 'jm', 'j.m.', 'einheit'],
  unitPrice: ['unit price', 'cena', 'cena jedn.', 'preis', 'price'],
  net: ['net', 'netto', 'net amount', 'betrag netto'],
  vat: ['vat', 'vat amount', 'mwst', 'podatek'],
  gross: ['gross', 'brutto', 'amount', 'total', 'kwota', 'betrag', 'gross amount'],
  currency: ['currency', 'waluta', 'währung', 'curr'],
  odometer: ['odometer', 'km', 'licznik', 'kilometerstand', 'mileage'],
};

// --- data --------------------------------------------------------------------------------------------

interface StoredCard extends Omit<FuelCard, 'cardNumberMasked' | 'vehicle' | 'driver' | 'createdAt' | 'updatedAt'> {
  number: string;
  vehicleId: number | null;
  driverId: number | null;
}

interface StoredTx extends Omit<FuelTransaction, 'card' | 'vehicle' | 'driver' | 'trip' | 'createdAt'> {
  cardId: number | null;
  vehicleId: number | null;
  driverId: number | null;
  tripId: number | null;
}

let nextId = 700;
const T0 = '2026-10-01T08:00:00Z';
const cards: StoredCard[] = [
  { id: ++nextId, provider: 'DKV', number: '7034123400001234', vehicleId: 1, driverId: null, validTo: '2027-06-30', active: true, notes: null },
  { id: ++nextId, provider: 'DKV', number: '7034123400005678', vehicleId: 2, driverId: null, validTo: '2027-06-30', active: true, notes: null },
  { id: ++nextId, provider: 'ORLEN', number: '7083990011112222', vehicleId: null, driverId: 3, validTo: '2026-12-31', active: true, notes: 'Karta kierowcy' },
];
const imports: FuelImport[] = [];
const txs: StoredTx[] = [];
const mappings = new Map<FuelProvider, FuelMapping>();

function seed(): void {
  const now = Date.now();
  const add = (daysAgo: number, cardId: number | null, vehicleId: number | null, station: string, country: string, product: FuelProduct, qty: number, price: number, currency = 'EUR') => {
    const at = new Date(now - daysAgo * 86_400_000).toISOString();
    const gross = r2(qty * price);
    const trip = vehicleId ? tripAtForFuel(vehicleId, at) : undefined;
    txs.push({
      id: ++nextId, at, provider: 'DKV', externalId: `DKV-${nextId}`, cardId, vehicleId, driverId: trip?.mainDriverId ?? null, tripId: trip?.id ?? null,
      station, country, product, productRaw: product === 'DIESEL' ? 'Diesel' : product === 'ADBLUE' ? 'AdBlue' : 'Maut', quantity: qty,
      unit: product === 'TOLL' ? 'PCS' : 'L', unitPrice: price, net: r2(gross / 1.19), vat: r2(gross - gross / 1.19), gross, currency, odometerKm: null,
      plateRaw: null, match: vehicleId ? 'CARD' : 'NONE', source: 'IMPORT', importId: 1, notes: null,
    });
  };
  add(1, cards[0].id, 1, 'DKV Bad Eilsen', 'DE', 'DIESEL', 410, 1.54);
  add(1, cards[0].id, 1, 'DKV Bad Eilsen', 'DE', 'ADBLUE', 30, 0.89);
  add(2, cards[1].id, 2, 'Shell Bremen', 'DE', 'DIESEL', 520, 1.61);
  add(4, cards[1].id, 2, 'Orlen Świecko', 'PL', 'DIESEL', 380, 6.12, 'PLN');
  add(6, cards[0].id, 1, 'Total Lyon Sud', 'FR', 'DIESEL', 450, 1.72);
  add(6, cards[0].id, 1, 'APRR', 'FR', 'TOLL', 1, 64.3);
  add(9, null, null, 'Esso Wrocław', 'PL', 'DIESEL', 300, 6.05, 'PLN');
  imports.push({
    id: 1, provider: 'DKV', fileName: 'dkv_transactions_october.csv', periodFrom: txs.at(-1)!.at.slice(0, 10), periodTo: txs[0].at.slice(0, 10),
    rowsTotal: 8, rowsNew: 7, rowsDup: 1, rowsError: 0, status: 'DONE', errors: [], mapping: {}, importedBy: { id: 1, name: 'Anna Kowalska' }, createdAt: new Date(now - 86_400_000).toISOString(),
  });
}
seed();

// --- helpers -----------------------------------------------------------------------------------------

const masked = (n: string) => `${n.slice(0, 4)} **** ${n.slice(-4)}`;
const plateKey = (p: string) => p.replace(/[\s-]+/g, '').toUpperCase();

function toCard(c: StoredCard, lk: FuelLookup): FuelCard {
  return {
    id: c.id, provider: c.provider, cardNumberMasked: masked(c.number), vehicle: c.vehicleId ? (lk.vehicle(c.vehicleId) ?? null) : null,
    driver: c.driverId ? { id: c.driverId, name: lk.driverName(c.driverId) ?? '?' } : null, validTo: c.validTo, active: c.active, notes: c.notes,
    createdAt: T0, updatedAt: T0,
  };
}

function toTx(t: StoredTx, lk: FuelLookup): FuelTransaction {
  const { cardId, vehicleId, driverId, tripId, ...rest } = t;
  const card = cardId ? cards.find((c) => c.id === cardId) : undefined;
  const t2 = tripId ? tripForInvoice(tripId) : undefined;
  const trip = t2 && { id: t2.id, name: t2.number };
  return {
    ...rest,
    createdAt: t.at,
    card: card ? { id: card.id, masked: masked(card.number) } : null,
    vehicle: vehicleId ? (lk.vehicle(vehicleId) ?? null) : null,
    driver: driverId ? { id: driverId, name: lk.driverName(driverId) ?? '?' } : null,
    trip: trip ?? null,
  };
}

/** Splits a CSV line on the separator, keeping quoted fields. */
function splitLine(line: string, sep: string): string[] {
  const out: string[] = [];
  let cur = '';
  let quoted = false;
  for (let i = 0; i < line.length; i++) {
    const ch = line[i];
    if (ch === '"') {
      if (quoted && line[i + 1] === '"') {
        cur += '"';
        i++;
      } else quoted = !quoted;
    } else if (ch === sep && !quoted) {
      out.push(cur.trim());
      cur = '';
    } else cur += ch;
  }
  out.push(cur.trim());
  return out;
}

function parseCsv(text: string): { headers: string[]; rows: string[][] } {
  const lines = text.replace(/^﻿/, '').split(/\r?\n/).filter((l) => l.trim() !== '');
  if (lines.length === 0) return { headers: [], rows: [] };
  const first = lines[0];
  const sep = [';', '\t', ','].sort((a, b) => first.split(b).length - first.split(a).length)[0];
  return { headers: splitLine(first, sep), rows: lines.slice(1).map((l) => splitLine(l, sep)) };
}

function detect(headers: string[]): FuelMapping {
  const mapping: FuelMapping = {};
  const used = new Set<string>();
  for (const f of MAPPING_FIELDS) {
    const h = headers.find((x) => !used.has(x) && SYNONYMS[f].includes(x.trim().toLowerCase()));
    if (h) {
      mapping[f] = h;
      used.add(h);
    }
  }
  return mapping;
}

function missingOf(mapping: FuelMapping): MappingField[] {
  const out: MappingField[] = [];
  if (!mapping.date) out.push('date');
  if (!mapping.product) out.push('product');
  if (!mapping.gross && !mapping.net) out.push('gross');
  if (!mapping.cardNumber && !mapping.plate) out.push('cardNumber');
  return out;
}

/** "1.234,56", "1,234.56", "1234,5" -> number; with both separators the last one is the decimal one. */
function parseNumber(raw: string | undefined, decimalComma: boolean | null): number | null | 'bad' {
  const s = (raw ?? '').replace(/[\s']/g, '');
  if (!s) return null;
  let n = s;
  if (n.includes(',') && n.includes('.')) n = n.lastIndexOf(',') > n.lastIndexOf('.') ? n.replace(/\./g, '').replace(',', '.') : n.replace(/,/g, '');
  else if (n.includes(',')) n = decimalComma === false ? n.replace(/,/g, '') : n.replace(',', '.');
  else if (n.includes('.') && decimalComma === true) n = n.replace(/\./g, '');
  const v = Number(n);
  return Number.isFinite(v) ? v : 'bad';
}

/** dd.MM.yyyy, yyyy-MM-dd, dd/MM/yyyy and HH:mm[:ss] -> instant, read in the firma zone (here: the browser's). */
function parseAt(date: string | undefined, time: string | undefined): string | 'bad' | null {
  const d = (date ?? '').trim();
  if (!d) return null;
  let m = /^(\d{4})-(\d{2})-(\d{2})(?:[ T](\d{1,2}):(\d{2}))?/.exec(d);
  let y: number, mo: number, da: number, h = 0, mi = 0;
  if (m) [y, mo, da] = [Number(m[1]), Number(m[2]), Number(m[3])];
  else {
    m = /^(\d{1,2})[./](\d{1,2})[./](\d{4})(?:\s+(\d{1,2}):(\d{2}))?/.exec(d);
    if (!m) return 'bad';
    [da, mo, y] = [Number(m[1]), Number(m[2]), Number(m[3])];
  }
  if (m[4]) [h, mi] = [Number(m[4]), Number(m[5])];
  const t = /^(\d{1,2}):(\d{2})/.exec((time ?? '').trim());
  if (t) [h, mi] = [Number(t[1]), Number(t[2])];
  const at = new Date(y, mo - 1, da, h, mi);
  return Number.isNaN(at.getTime()) || at.getMonth() !== mo - 1 ? 'bad' : at.toISOString();
}

function productOf(raw: string): FuelProduct {
  const s = raw.toLowerCase();
  if (/adblue|def\b/.test(s)) return 'ADBLUE';
  if (/diesel|^on\b|olej nap|gazole|gasoil/.test(s)) return 'DIESEL';
  if (/lng/.test(s)) return 'LNG';
  if (/cng/.test(s)) return 'CNG';
  if (/benz|petrol|super|95|98|essence/.test(s)) return 'PETROL';
  if (/toll|maut|myto|opłata|peage|péage|viat/.test(s)) return 'TOLL';
  if (/wash|myjnia|service|serwis|parking/.test(s)) return 'SERVICE';
  return 'OTHER';
}

function hash(s: string): string {
  let h = 0;
  for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) | 0;
  return (h >>> 0).toString(16);
}

interface ReadRow {
  row: number;
  values: FuelPreviewValues;
  errors: Partial<Record<MappingField, string>>;
}

function readRow(cells: string[], headers: string[], mapping: FuelMapping, options: FuelReadOptions, row: number): ReadRow {
  const cell = (f: MappingField) => {
    const h = mapping[f];
    const i = h ? headers.indexOf(h) : -1;
    return i >= 0 ? cells[i] : undefined;
  };
  const errors: ReadRow['errors'] = {};
  const number = (f: MappingField) => {
    const v = parseNumber(cell(f), options.decimalComma);
    if (v === 'bad') {
      errors[f] = 'validation.invalid_format';
      return null;
    }
    return v;
  };
  const at = parseAt(cell('date'), cell('time'));
  if (at === 'bad') errors.date = 'validation.invalid_format';
  else if (at === null) errors.date = 'validation.required';
  const card = blank(cell('cardNumber'))?.replace(/\s/g, '') ?? null;
  const productRaw = blank(cell('product'));
  const unitRaw = (blank(cell('unit')) ?? '').toUpperCase();
  const values: FuelPreviewValues = {
    at: typeof at === 'string' && at !== 'bad' ? at : null,
    cardNumber: card ? masked(card) : null,
    plate: blank(cell('plate')),
    transactionId: blank(cell('transactionId')),
    station: blank(cell('station')),
    country: blank(cell('country'))?.toUpperCase().slice(0, 2) ?? null,
    product: productRaw ? productOf(productRaw) : null,
    productRaw,
    quantity: number('quantity'),
    unit: unitRaw.startsWith('L') ? 'L' : unitRaw.startsWith('KG') ? 'KG' : unitRaw ? 'PCS' : null,
    unitPrice: number('unitPrice'),
    net: number('net'),
    vat: number('vat'),
    gross: number('gross'),
    currency: (blank(cell('currency')) ?? options.currency ?? 'EUR').toUpperCase(),
    odometer: number('odometer'),
  };
  if (!productRaw) errors.product = 'validation.required';
  if (values.gross == null && values.net == null && !errors.gross && !errors.net) errors.gross = 'validation.required';
  if (!card && !values.plate) errors.cardNumber = 'validation.required';
  return { row, values, errors };
}

/** The full card number of a row (the preview shows it masked). */
function cardOfRow(cells: string[], headers: string[], mapping: FuelMapping): string | null {
  const h = mapping.cardNumber;
  const i = h ? headers.indexOf(h) : -1;
  return i >= 0 ? (blank(cells[i])?.replace(/\s/g, '') ?? null) : null;
}

/** mapping and options form fields (JSON strings); undefined when absent, 'bad' when not JSON. */
function jsonField<T>(body: unknown, name: string): T | undefined | 'bad' {
  const raw = body instanceof FormData ? body.get(name) : null;
  if (raw == null || raw === '') return undefined;
  try {
    return JSON.parse(String(raw)) as T;
  } catch {
    return 'bad';
  }
}

const AUTO_OPTIONS: FuelReadOptions = { dateFormat: null, decimalComma: null, currency: null };

async function fileOf(body: unknown): Promise<{ name: string; text: string } | MockReply> {
  const file = body instanceof FormData ? body.get('file') : null;
  if (!(file instanceof File)) return invalid({ file: 'validation.required' });
  if (file.size > MAX_BYTES) return invalid({ file: 'fuel.import.too_large' });
  if (/\.(xlsx|xls)$/i.test(file.name)) return invalid({ file: 'fuel.import.unreadable' });
  return { name: file.name, text: await file.text() };
}

const providerOf = (body: unknown): FuelProvider | null => {
  const p = body instanceof FormData ? String(body.get('provider') ?? '') : '';
  return FUEL_PROVIDERS.includes(p as FuelProvider) ? (p as FuelProvider) : null;
};

// --- imports -----------------------------------------------------------------------------------------

export async function preview(body: unknown): Promise<MockReply> {
  const f = await fileOf(body);
  if ('status' in f) return f;
  const { headers, rows } = parseCsv(f.text);
  if (headers.length === 0) return invalid({ file: 'fuel.import.unreadable' });
  const provider = providerOf(body) ?? (/dkv/i.test(f.name) ? 'DKV' : /orlen/i.test(f.name) ? 'ORLEN' : 'OTHER');
  const given = jsonField<FuelMapping>(body, 'mapping');
  const opts = jsonField<FuelReadOptions>(body, 'options');
  if (given === 'bad') return invalid({ mapping: 'validation.invalid_format' });
  if (opts === 'bad') return invalid({ options: 'validation.invalid_format' });
  const saved = mappings.get(provider);
  const mapping = given ?? (saved && Object.values(saved).every((h) => !h || headers.includes(h)) ? saved : detect(headers));
  const options = { ...AUTO_OPTIONS, ...(opts ?? {}) };
  const full = Object.fromEntries(MAPPING_FIELDS.map((k) => [k, mapping[k] ?? null])) as FuelMapping;
  const preview: FuelPreview = {
    provider,
    headerRow: 1,
    headers,
    mapping: full,
    missing: missingOf(full),
    options,
    dataRows: rows.length,
    rows: rows.slice(0, 10).map((cells, i): FuelPreviewRow => {
      const r = readRow(cells, headers, full, options, i + 2);
      return { row: r.row, values: r.values, errors: r.errors };
    }),
  };
  return { status: 200, body: preview };
}

export async function runImport(body: unknown, lk: FuelLookup): Promise<MockReply> {
  const f = await fileOf(body);
  if ('status' in f) return f;
  const provider = providerOf(body);
  if (!provider) return invalid({ provider: 'validation.invalid_value' });
  const { headers, rows } = parseCsv(f.text);
  if (headers.length === 0) return invalid({ file: 'fuel.import.unreadable' });
  if (rows.length > MAX_ROWS) return invalid({ file: 'fuel.import.too_large' });
  const given = jsonField<FuelMapping>(body, 'mapping');
  const opts = jsonField<FuelReadOptions>(body, 'options');
  if (given === 'bad') return invalid({ mapping: 'validation.invalid_format' });
  if (opts === 'bad') return invalid({ options: 'validation.invalid_format' });
  const mapping = given ?? mappings.get(provider) ?? detect(headers);
  const options = { ...AUTO_OPTIONS, ...(opts ?? {}) };
  const missing = missingOf(mapping);
  if (missing.length) {
    return invalid({ file: 'fuel.import.columns_missing', ...Object.fromEntries(missing.map((m) => [`columns.${m}`, 'validation.required'])) });
  }
  if (body instanceof FormData && body.get('saveMapping') !== 'false') mappings.set(provider, mapping);

  const id = ++nextId;
  const result: FuelImport = {
    id, provider, fileName: f.name, periodFrom: null, periodTo: null, rowsTotal: rows.length, rowsNew: 0, rowsDup: 0, rowsError: 0,
    status: 'DONE', errors: [], mapping, importedBy: lk.me, createdAt: new Date().toISOString(),
  };
  rows.forEach((cells, i) => {
    const r = readRow(cells, headers, mapping, options, i + 2);
    const errs = Object.entries(r.errors);
    const v = r.values;
    if (errs.length || !v.at) {
      result.rowsError++;
      for (const [field, code] of errs) result.errors.push({ row: r.row, field, code: code as string });
      return;
    }
    const number = cardOfRow(cells, headers, mapping);
    const gross = v.gross ?? r2((v.net ?? 0) + (v.vat ?? 0));
    const externalId = v.transactionId ?? `H:${hash([number, v.at, v.product, v.quantity, gross].join('|'))}`;
    if (txs.some((t) => t.provider === provider && t.externalId === externalId)) {
      result.rowsDup++;
      return;
    }
    // Unknown cards are created; the card's vehicle and driver win over the plate column.
    let card = number ? cards.find((c) => c.provider === provider && c.number === number) : undefined;
    if (!card && number) {
      card = { id: ++nextId, provider, number, vehicleId: null, driverId: null, validTo: null, active: true, notes: null };
      cards.push(card);
    }
    const byPlate = v.plate ? lk.vehicleByPlate(v.plate) : undefined;
    const vehicleId = card?.vehicleId ?? byPlate?.id ?? null;
    const trip = vehicleId ? tripAtForFuel(vehicleId, v.at) : undefined;
    txs.push({
      id: ++nextId, at: v.at, provider, externalId, cardId: card?.id ?? null, vehicleId, driverId: card?.driverId ?? trip?.mainDriverId ?? null,
      tripId: trip?.id ?? null, station: v.station, country: v.country, product: v.product ?? 'OTHER', productRaw: v.productRaw,
      quantity: v.quantity, unit: v.unit ?? (v.quantity != null ? 'L' : null), unitPrice: v.unitPrice, net: v.net, vat: v.vat, gross,
      currency: v.currency ?? 'EUR', odometerKm: v.odometer, plateRaw: v.plate,
      match: card?.vehicleId ? 'CARD' : byPlate ? 'PLATE' : 'NONE', source: 'IMPORT', importId: id, notes: null,
    });
    result.rowsNew++;
    const day = v.at.slice(0, 10);
    if (!result.periodFrom || day < result.periodFrom) result.periodFrom = day;
    if (!result.periodTo || day > result.periodTo) result.periodTo = day;
  });
  imports.unshift(result);
  return { status: 201, body: result };
}

export function listImports(query: Record<string, string>): MockReply {
  const size = Math.min(Math.max(Number(query.size ?? 50) || 50, 1), 200);
  const from = (Number(query.page ?? 0) || 0) * size;
  return { status: 200, body: { items: imports.slice(from, from + size), total: imports.length } };
}

export function getImport(id: string): MockReply {
  const i = imports.find((x) => String(x.id) === id);
  return i ? { status: 200, body: i } : notFound();
}

export function removeImport(id: string): MockReply {
  const i = imports.findIndex((x) => String(x.id) === id);
  if (i < 0) return notFound();
  for (let n = txs.length - 1; n >= 0; n--) if (txs[n].importId === imports[i].id) txs.splice(n, 1);
  imports.splice(i, 1);
  return { status: 204 };
}

// --- transactions ------------------------------------------------------------------------------------

export function listTransactions(query: Record<string, string>, lk: FuelLookup): MockReply {
  const needle = (query.q ?? '').trim().toLowerCase();
  const all = txs
    .filter((t) => !query.from || t.at.slice(0, 10) >= query.from)
    .filter((t) => !query.to || t.at.slice(0, 10) <= query.to)
    .filter((t) => !query.vehicleId || String(t.vehicleId) === query.vehicleId)
    .filter((t) => !query.driverId || String(t.driverId) === query.driverId)
    .filter((t) => !query.cardId || String(t.cardId) === query.cardId)
    .filter((t) => !query.tripId || String(t.tripId) === query.tripId)
    .filter((t) => !query.importId || String(t.importId) === query.importId)
    .filter((t) => !query.product || t.product === query.product)
    .filter((t) => !query.country || t.country === query.country)
    .filter((t) => query.unmatched !== 'true' || t.vehicleId == null)
    .map((t) => toTx(t, lk))
    .filter((t) => !needle || [t.station, t.productRaw, t.plateRaw, t.vehicle?.name, t.externalId].some((s) => (s ?? '').toLowerCase().includes(needle)))
    .sort((a, b) => b.at.localeCompare(a.at) || b.id - a.id);
  const totals = new Map<string, { net: number; vat: number; gross: number }>();
  const qty = new Map<string, number>();
  for (const t of all) {
    const s = totals.get(t.currency) ?? { net: 0, vat: 0, gross: 0 };
    totals.set(t.currency, { net: r2(s.net + (t.net ?? 0)), vat: r2(s.vat + (t.vat ?? 0)), gross: r2(s.gross + t.gross) });
    if (t.quantity != null && t.unit) qty.set(`${t.product}|${t.unit}`, r2((qty.get(`${t.product}|${t.unit}`) ?? 0) + t.quantity));
  }
  const size = Math.min(Math.max(Number(query.size ?? 50) || 50, 1), 200);
  const from = (Number(query.page ?? 0) || 0) * size;
  return {
    status: 200,
    body: {
      items: all.slice(from, from + size),
      total: all.length,
      totals: [...totals].map(([currency, s]) => ({ currency, ...s })),
      quantities: [...qty].map(([k, quantity]) => {
        const [product, unit] = k.split('|');
        return { product, unit, quantity };
      }),
    },
  };
}

export function getTransaction(id: string, lk: FuelLookup): MockReply {
  const t = txs.find((x) => String(x.id) === id);
  return t ? { status: 200, body: toTx(t, lk) } : notFound();
}

export function saveTransaction(b: FuelTransactionInput, id: string | null, lk: FuelLookup): MockReply {
  const target = id == null ? undefined : txs.find((x) => String(x.id) === id);
  if (id != null && !target) return notFound();
  // Imported rows keep what the file said; only their assignment changes (PATCH).
  if (target && target.source !== 'MANUAL') return fail(409, 'error.conflict');
  const errors: Record<string, string> = {};
  if (b.vehicleId != null && !lk.vehicle(b.vehicleId)) errors.vehicleId = 'validation.invalid_value';
  if (b.driverId != null && !lk.driverName(b.driverId)) errors.driverId = 'validation.invalid_value';
  if (!b.at) errors.at = 'validation.required';
  if (b.gross == null) errors.gross = 'validation.required';
  if (!/^[A-Z]{3}$/.test((b.currency ?? '').toUpperCase())) errors.currency = b.currency ? 'validation.invalid_format' : 'validation.required';
  if (!b.product) errors.product = 'validation.required';
  if (Object.keys(errors).length) return invalid(errors);
  const trip = b.vehicleId && b.tripId == null ? tripAtForFuel(b.vehicleId, b.at) : undefined;
  const value = {
    at: b.at, provider: null, externalId: null, cardId: b.cardId, vehicleId: b.vehicleId, driverId: b.driverId, tripId: b.tripId ?? trip?.id ?? null,
    station: blank(b.station), country: blank(b.country), product: b.product, productRaw: null, quantity: b.quantity, unit: b.unit,
    unitPrice: b.unitPrice, net: b.net, vat: b.vat, gross: b.gross, currency: b.currency.toUpperCase(), odometerKm: b.odometerKm, plateRaw: null,
    match: (b.vehicleId || b.driverId ? 'MANUAL' : 'NONE') as StoredTx['match'], source: 'MANUAL' as const, importId: null, notes: blank(b.notes),
  };
  if (target) {
    Object.assign(target, value);
    return { status: 200, body: toTx(target, lk) };
  }
  const created: StoredTx = { ...value, id: ++nextId };
  txs.push(created);
  return { status: 201, body: toTx(created, lk) };
}

export function assign(id: string, b: FuelAssignment, lk: FuelLookup): MockReply {
  const target = txs.find((x) => String(x.id) === id);
  if (!target) return notFound();
  const errors: Record<string, string> = {};
  if (b.vehicleId != null && !lk.vehicle(b.vehicleId)) errors.vehicleId = 'validation.invalid_value';
  if (b.driverId != null && !lk.driverName(b.driverId)) errors.driverId = 'validation.invalid_value';
  if (b.tripId != null && !tripForInvoice(b.tripId)) errors.tripId = 'validation.invalid_value';
  if ((b.notes ?? '').length > 500) errors.notes = 'validation.too_long';
  if (Object.keys(errors).length) return invalid(errors);
  Object.assign(target, {
    vehicleId: b.vehicleId, driverId: b.driverId, tripId: b.tripId, notes: blank(b.notes),
    match: b.vehicleId == null && b.driverId == null ? 'NONE' : 'MANUAL',
  });
  return { status: 200, body: toTx(target, lk) };
}

export function removeTransaction(id: string): MockReply {
  const i = txs.findIndex((x) => String(x.id) === id);
  if (i < 0) return notFound();
  txs.splice(i, 1);
  return { status: 204 };
}

// --- cards -------------------------------------------------------------------------------------------

export function listCards(lk: FuelLookup): MockReply {
  return { status: 200, body: [...cards].sort((a, b) => a.provider.localeCompare(b.provider) || a.number.localeCompare(b.number)).map((c) => toCard(c, lk)) };
}

export function getCard(id: string, lk: FuelLookup): MockReply {
  const c = cards.find((x) => String(x.id) === id);
  return c ? { status: 200, body: toCard(c, lk) } : notFound();
}

export function saveCard(b: FuelCardInput, id: string | null, lk: FuelLookup): MockReply {
  const target = id == null ? undefined : cards.find((x) => String(x.id) === id);
  if (id != null && !target) return notFound();
  const errors: Record<string, string> = {};
  if (!FUEL_PROVIDERS.includes(b.provider)) errors.provider = 'validation.required';
  const number = (b.cardNumber ?? '').replace(/[\s-]/g, '');
  if (!target && !number) errors.cardNumber = 'validation.required';
  else if (number && !/^\d{6,24}$/.test(number)) errors.cardNumber = 'validation.invalid_format';
  else if (number && cards.some((c) => c.provider === b.provider && c.number === number && c.id !== target?.id)) errors.cardNumber = 'fuel.card.number_taken';
  if (b.vehicleId != null && !lk.vehicle(b.vehicleId)) errors.vehicleId = 'validation.invalid_value';
  if (b.driverId != null && !lk.driverName(b.driverId)) errors.driverId = 'validation.invalid_value';
  if (Object.keys(errors).length) return invalid(errors);
  const value = { provider: b.provider, vehicleId: b.vehicleId, driverId: b.driverId, validTo: b.validTo, active: b.active, notes: blank(b.notes) };
  if (target) {
    Object.assign(target, value, number ? { number } : {});
    return { status: 200, body: toCard(target, lk) };
  }
  const created: StoredCard = { ...value, id: ++nextId, number };
  cards.push(created);
  return { status: 201, body: toCard(created, lk) };
}

export function removeCard(id: string): MockReply {
  const i = cards.findIndex((x) => String(x.id) === id);
  if (i < 0) return notFound();
  // Transactions keep their data; they only lose the card link.
  for (const t of txs) if (t.cardId === cards[i].id) t.cardId = null;
  cards.splice(i, 1);
  return { status: 204 };
}

export function plateMatches(a: string, b: string): boolean {
  return plateKey(a) === plateKey(b);
}

/** For the calculator mock: average DIESEL unit price of the last 30 days in the most used currency. */
export function avgDieselPrice(): { price: number; currency: string } | undefined {
  const since = new Date(Date.now() - 30 * 86_400_000).toISOString();
  const rows = txs.filter((t) => t.product === 'DIESEL' && t.unitPrice != null && t.at >= since);
  if (rows.length === 0) return undefined;
  const byCur = new Map<string, number[]>();
  for (const t of rows) byCur.set(t.currency, [...(byCur.get(t.currency) ?? []), t.unitPrice as number]);
  const [currency, prices] = [...byCur].sort((a, b) => b[1].length - a[1].length)[0];
  return { price: Math.round((prices.reduce((s, p) => s + p, 0) / prices.length) * 1000) / 1000, currency };
}
