import type { Cmr, CmrInput, CmrLine, CmrParty, CmrStatus } from '@/api/cmr';
import { CMR_STATUSES, PAYMENT_TERMS } from '@/api/cmr';
import type { CmrPlace, CrewLookup, MockReply } from './trips';
import { contractorForCmr, orderForCmr, tripForCmr } from './trips';

/*
 * CMR consignment notes for the in-browser mock API, with the rules of CmrService: numbers CMR/yyyy/NNNN of the loading
 * year, drafts only can change, issuing checks the boxes a CMR cannot do without; the PDF is a one-line-per-box sketch.
 */

export interface CmrLookup {
  crew: CrewLookup;
  /** The own firma as the carrier (box 16). */
  firma: CmrParty;
}

const fail = (status: number, code: string, details?: Record<string, string>): MockReply => ({
  status,
  body: { status, code, message: code, ...(details ? { details } : {}) },
});
const invalid = (details: Record<string, string>) => fail(422, 'error.validation', details);
const notFound = () => fail(404, 'error.not_found');
const conflict = () => fail(409, 'error.conflict');

const NUMBER_RE = /^[\p{L}\p{N}][\p{L}\p{N} ./_-]*$/u;
const COUNTRY_RE = /^[A-Z]{2}$/;
const CURRENCY_RE = /^[A-Z]{3}$/;
const blank = (s: string | null | undefined) => (s ?? '').trim() || null;

interface MockCmr extends Omit<Cmr, 'order' | 'trip'> {
  orderId: number | null;
  tripId: number | null;
  /** Pages of the stored PDF (the four copies once issued). */
  pdfCopies: number;
}

const notes: MockCmr[] = [];

const nextId = () => Math.max(0, ...notes.map((x) => x.id)) + 1;

function nextNumber(loadingDate: string | null): string {
  const prefix = `CMR/${loadingDate ? loadingDate.slice(0, 4) : new Date().getFullYear()}/`;
  const n = Math.max(0, ...notes.map((x) => x.number).filter((x) => x.startsWith(prefix)).map((x) => Number(x.slice(prefix.length)) || 0)) + 1;
  return `${prefix}${String(n).padStart(4, '0')}`;
}

function toCmr(c: MockCmr, crew: CrewLookup): Cmr {
  const { orderId, tripId, pdfCopies: _p, ...rest } = c;
  const o = orderId == null ? undefined : orderForCmr(orderId);
  const t = tripId == null ? undefined : tripForCmr(tripId, crew);
  return { ...rest, order: o ? { id: o.id, number: o.number } : null, trip: t ? { id: t.id, number: t.number } : null };
}

const find = (id: string | number) => notes.find((x) => x.id === Number(id));

// --- reading -----------------------------------------------------------------------------------------

export function listCmr(query: Record<string, string>, crew: CrewLookup): MockReply {
  const errors: Record<string, string> = {};
  if (query.status && !(CMR_STATUSES as readonly string[]).includes(query.status)) errors.status = 'validation.invalid_value';
  if (Object.keys(errors).length) return invalid(errors);
  const q = (query.q ?? '').trim().toLowerCase();
  const hit = (c: MockCmr) =>
    [c.number, c.sender?.name, c.consignee?.name, c.carrier?.name, c.loadingPlace, c.deliveryPlace].some((s) => s?.toLowerCase().includes(q));
  const items = notes
    .filter((c) => !query.status || c.status === query.status)
    .filter((c) => !query.orderId || c.orderId === Number(query.orderId))
    .filter((c) => !query.tripId || c.tripId === Number(query.tripId))
    .filter((c) => !q || hit(c))
    .filter((c) => !query.from || (c.loadingDate != null && c.loadingDate >= query.from))
    .filter((c) => !query.to || (c.loadingDate != null && c.loadingDate <= query.to))
    .sort((a, b) => b.createdAt.localeCompare(a.createdAt) || b.id - a.id);
  const size = Math.min(Math.max(Number(query.size ?? 50) || 50, 1), 200);
  const from = (Number(query.page ?? 0) || 0) * size;
  return { status: 200, body: { items: items.slice(from, from + size).map((c) => toCmr(c, crew)), total: items.length } };
}

export function getCmr(id: string, crew: CrewLookup): MockReply {
  const c = find(id);
  return c ? { status: 200, body: toCmr(c, crew) } : notFound();
}

// --- the full replacement ----------------------------------------------------------------------------

function party(field: string, p: CmrParty | null | undefined, errors: Record<string, string>): CmrParty | null {
  if (!p) return null;
  const country = blank(p.country)?.toUpperCase() ?? null;
  if (country && !COUNTRY_RE.test(country)) errors[`${field}.country`] = 'validation.invalid_format';
  const clean = { name: blank(p.name), address: blank(p.address), country, vatId: blank(p.vatId) };
  return clean.name || clean.address || clean.country || clean.vatId ? clean : null;
}

function country(field: string, v: string | null | undefined, errors: Record<string, string>): string | null {
  const c = blank(v)?.toUpperCase() ?? null;
  if (c && !COUNTRY_RE.test(c)) errors[field] = 'validation.invalid_format';
  return c;
}

/** Validates and writes the input into the note (new when `c` has no id yet). */
function apply(c: MockCmr, b: Partial<CmrInput>, crew: CrewLookup): MockReply | null {
  const errors: Record<string, string> = {};
  const number = blank(b.number)?.toUpperCase() ?? null;
  if (number && !NUMBER_RE.test(number)) errors.number = 'validation.invalid_format';
  else if (number && notes.some((x) => x.number === number && x.id !== c.id)) errors.number = 'trip.cmr.number_taken';
  if (b.orderId != null && !orderForCmr(b.orderId)) errors.orderId = 'validation.invalid_value';
  if (b.tripId != null && !tripForCmr(b.tripId, crew)) errors.tripId = 'validation.invalid_value';
  if (b.senderContractorId != null && !contractorForCmr(b.senderContractorId)) errors.senderContractorId = 'validation.invalid_value';
  if (b.consigneeContractorId != null && !contractorForCmr(b.consigneeContractorId)) errors.consigneeContractorId = 'validation.invalid_value';
  const sender = party('sender', b.sender, errors);
  const consignee = party('consignee', b.consignee, errors);
  const carrier = party('carrier', b.carrier, errors);
  if ((b.successiveCarriers ?? []).length > 5) errors.successiveCarriers = 'validation.too_long';
  const successive = (b.successiveCarriers ?? []).map((p, i) => party(`successiveCarriers[${i}]`, p, errors)).filter((p): p is CmrParty => !!p);
  const deliveryCountry = country('deliveryCountry', b.deliveryCountry, errors);
  const loadingCountry = country('loadingCountry', b.loadingCountry, errors);
  if ((b.lines ?? []).length > 30) errors.lines = 'validation.too_long';
  const lines: CmrLine[] = [];
  (b.lines ?? []).forEach((l, i) => {
    if (!l) return;
    if (l.packages != null && (l.packages < 0 || l.packages > 1_000_000 || !Number.isInteger(l.packages))) errors[`lines[${i}].packages`] = 'validation.invalid_value';
    if (l.grossWeightKg != null && l.grossWeightKg < 0) errors[`lines[${i}].grossWeightKg`] = 'validation.invalid_value';
    if (l.volumeM3 != null && l.volumeM3 < 0) errors[`lines[${i}].volumeM3`] = 'validation.invalid_value';
    const clean: CmrLine = {
      marks: blank(l.marks),
      packages: l.packages ?? null,
      packing: blank(l.packing),
      nature: blank(l.nature),
      statNumber: blank(l.statNumber),
      grossWeightKg: l.grossWeightKg == null ? null : Math.round(l.grossWeightKg * 10) / 10,
      volumeM3: l.volumeM3 == null ? null : Math.round(l.volumeM3 * 1000) / 1000,
    };
    if (Object.values(clean).some((v) => v != null)) lines.push(clean);
  });
  const terms = blank(b.paymentTerms);
  if (terms && !(PAYMENT_TERMS as readonly string[]).includes(terms)) errors.paymentTerms = 'validation.invalid_value';
  let cod: Cmr['cashOnDelivery'] = null;
  if (b.cashOnDelivery?.amount != null) {
    const currency = blank(b.cashOnDelivery.currency)?.toUpperCase() ?? null;
    if (!currency) errors['cashOnDelivery.currency'] = 'validation.required';
    else if (!CURRENCY_RE.test(currency)) errors['cashOnDelivery.currency'] = 'validation.invalid_format';
    cod = { amount: Math.round(b.cashOnDelivery.amount * 100) / 100, currency: currency ?? '' };
  }
  if (Object.keys(errors).length) return invalid(errors);

  const sig = (s: { name: string | null; place: string | null; date: string | null } | null | undefined) => {
    const clean = { name: blank(s?.name), place: blank(s?.place), date: s?.date || null };
    return clean.name || clean.place || clean.date ? clean : null;
  };
  Object.assign(c, {
    number: number ?? (c.number || nextNumber(b.loadingDate ?? null)),
    orderId: b.orderId ?? null,
    tripId: b.tripId ?? null,
    sender,
    senderContractorId: b.senderContractorId ?? null,
    consignee,
    consigneeContractorId: b.consigneeContractorId ?? null,
    carrier,
    successiveCarriers: successive,
    deliveryPlace: blank(b.deliveryPlace),
    deliveryCountry,
    loadingPlace: blank(b.loadingPlace),
    loadingCountry,
    loadingDate: b.loadingDate || null,
    documentsAttached: blank(b.documentsAttached),
    lines,
    senderInstructions: blank(b.senderInstructions),
    paymentTerms: (terms as Cmr['paymentTerms']) ?? null,
    paymentInstructions: blank(b.paymentInstructions),
    cashOnDelivery: cod,
    carrierReservations: blank(b.carrierReservations),
    specialAgreements: blank(b.specialAgreements),
    establishedPlace: blank(b.establishedPlace),
    establishedDate: b.establishedDate || null,
    vehiclePlates: blank(b.vehiclePlates),
    signatures: b.signatures ? { sender: sig(b.signatures.sender), carrier: sig(b.signatures.carrier), consignee: sig(b.signatures.consignee) } : null,
    notes: blank(b.notes),
    updatedAt: new Date().toISOString(),
  });
  return null;
}

function draft(): MockCmr {
  const now = new Date().toISOString();
  return {
    id: 0, number: '', status: 'DRAFT', orderId: null, tripId: null, sender: null, senderContractorId: null, consignee: null,
    consigneeContractorId: null, carrier: null, successiveCarriers: [], deliveryPlace: null, deliveryCountry: null, loadingPlace: null,
    loadingCountry: null, loadingDate: null, documentsAttached: null, lines: [], senderInstructions: null, paymentTerms: null,
    paymentInstructions: null, cashOnDelivery: null, carrierReservations: null, specialAgreements: null, establishedPlace: null,
    establishedDate: null, vehiclePlates: null, signatures: null, notes: null, hasPdf: false, issuedAt: null, createdAt: now,
    updatedAt: now, pdfCopies: 0,
  };
}

function create(b: Partial<CmrInput>, crew: CrewLookup): MockCmr | MockReply {
  const c = draft();
  const err = apply(c, b, crew);
  if (err) return err;
  c.id = nextId();
  notes.push(c);
  return c;
}

export function saveCmr(b: Partial<CmrInput>, id: string | null, crew: CrewLookup): MockReply {
  if (id === null) {
    const c = create(b, crew);
    return 'id' in c ? { status: 201, body: toCmr(c, crew) } : c;
  }
  const c = find(id);
  if (!c) return notFound();
  if (c.status !== 'DRAFT') return conflict();
  const err = apply(c, b, crew);
  return err ?? { status: 200, body: toCmr(c, crew) };
}

export function removeCmr(id: string): MockReply {
  const i = notes.findIndex((x) => x.id === Number(id));
  if (i < 0) return notFound();
  if (notes[i].status !== 'DRAFT') return conflict();
  notes.splice(i, 1);
  return { status: 204 };
}

// --- drafts from an order or a trip ------------------------------------------------------------------

const city = (place: string | null | undefined) => (place ? place.split(',')[0].trim().slice(0, 100) || null : null);

function fromOrderInput(orderId: number, lk: CmrLookup): Partial<CmrInput> | null {
  const o = orderForCmr(orderId);
  if (!o) return null;
  const trip = o.tripId == null ? undefined : tripForCmr(o.tripId, lk.crew);
  return {
    orderId: o.id,
    tripId: trip?.id ?? null,
    sender: { name: o.customer.name, address: o.customer.address, country: o.customer.country, vatId: o.customer.vatId },
    senderContractorId: o.customer.id,
    consignee: { name: null, address: o.unloading.place, country: o.unloading.country, vatId: null },
    carrier: lk.firma,
    deliveryPlace: o.unloading.place,
    deliveryCountry: o.unloading.country,
    loadingPlace: o.loading.place,
    loadingCountry: o.loading.country,
    loadingDate: o.loading.date,
    lines: o.cargo == null && o.weightKg == null ? [] : [{ marks: null, packages: null, packing: null, nature: o.cargo, statNumber: null, grossWeightKg: o.weightKg, volumeM3: null }],
    establishedPlace: city(o.loading.place),
    establishedDate: o.loading.date,
    vehiclePlates: trip?.plates ?? null,
  };
}

function fromStopsInput(tripId: number, loading: CmrPlace | null, unloading: CmrPlace | null, plates: string | null, lk: CmrLookup): Partial<CmrInput> {
  const stop = (s: CmrPlace | null) => (s ? { name: s.name ?? null, address: s.place, country: s.country, vatId: null } : null);
  return {
    tripId,
    sender: stop(loading),
    consignee: stop(unloading),
    carrier: lk.firma,
    deliveryPlace: unloading?.place ?? null,
    deliveryCountry: unloading?.country ?? null,
    loadingPlace: loading?.place ?? null,
    loadingCountry: loading?.country ?? null,
    loadingDate: loading?.date ?? null,
    lines: [],
    establishedPlace: city(loading?.place),
    establishedDate: loading?.date ?? null,
    vehiclePlates: plates,
  };
}

export function cmrFromOrder(orderId: string, lk: CmrLookup): MockReply {
  const input = fromOrderInput(Number(orderId), lk);
  if (!input) return notFound();
  const c = create(input, lk.crew);
  return 'id' in c ? { status: 201, body: toCmr(c, lk.crew) } : c;
}

export function cmrFromTrip(tripId: string, lk: CmrLookup): MockReply {
  const t = tripForCmr(Number(tripId), lk.crew);
  if (!t) return notFound();
  const inputs = t.orderIds.length
    ? t.orderIds.map((id) => ({ ...fromOrderInput(id, lk)!, tripId: t.id, vehiclePlates: t.plates }))
    : [fromStopsInput(t.id, t.loading, t.unloading, t.plates, lk)];
  const made: Cmr[] = [];
  for (const input of inputs) {
    const c = create(input, lk.crew);
    if (!('id' in c)) return c;
    made.push(toCmr(c, lk.crew));
  }
  return { status: 201, body: made };
}

// --- issue, cancel, PDF ------------------------------------------------------------------------------

export function issueCmr(id: string, crew: CrewLookup): MockReply {
  const c = find(id);
  if (!c) return notFound();
  if (c.status !== 'DRAFT') return conflict();
  const errors: Record<string, string> = {};
  if (!c.sender?.name) errors['sender.name'] = 'validation.required';
  if (!c.consignee?.name && !c.deliveryPlace) errors['consignee.name'] = 'validation.required';
  if (!c.carrier?.name) errors['carrier.name'] = 'validation.required';
  if (!c.loadingPlace) errors.loadingPlace = 'validation.required';
  if (!c.deliveryPlace) errors.deliveryPlace = 'validation.required';
  if (!c.lines.some((l) => l.nature)) errors.lines = 'validation.required';
  if (Object.keys(errors).length) return invalid(errors);
  const now = new Date().toISOString();
  Object.assign(c, { status: 'ISSUED' as CmrStatus, issuedAt: now, hasPdf: true, pdfCopies: 4, updatedAt: now });
  return { status: 200, body: toCmr(c, crew) };
}

export function cancelCmr(id: string, crew: CrewLookup): MockReply {
  const c = find(id);
  if (!c) return notFound();
  if (c.status === 'CANCELLED') return conflict();
  c.status = 'CANCELLED';
  c.updatedAt = new Date().toISOString();
  return { status: 200, body: toCmr(c, crew) };
}

/** A minimal valid PDF: one page per copy, one line per filled box (ASCII only, like a sketch of the real form). */
export function sketchPdf(pages: string[][]): Blob {
  const esc = (s: string) => s.replace(/[^\x20-\x7e]/g, '?').replace(/([()\\])/g, '\\$1');
  const objects = [
    '<< /Type /Catalog /Pages 2 0 R >>',
    `<< /Type /Pages /Kids [${pages.map((_, i) => `${4 + i * 2} 0 R`).join(' ')}] /Count ${pages.length} >>`,
    '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
  ];
  pages.forEach((lines, i) => {
    const stream = ['BT', '/F1 11 Tf', '50 800 Td', '15 TL', ...lines.map((l) => `(${esc(l)}) '`), 'ET'].join('\n');
    objects.push(`<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 3 0 R >> >> /Contents ${5 + i * 2} 0 R >>`);
    objects.push(`<< /Length ${stream.length} >>\nstream\n${stream}\nendstream`);
  });
  let out = '%PDF-1.4\n';
  const offsets = objects.map((o, i) => {
    const at = out.length;
    out += `${i + 1} 0 obj\n${o}\nendobj\n`;
    return at;
  });
  const xref = out.length;
  out += `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n${offsets.map((o) => `${String(o).padStart(10, '0')} 00000 n \n`).join('')}`;
  out += `trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`;
  return new Blob([out], { type: 'application/pdf' });
}

const COPY_NAMES = ['Copy for sender', 'Copy for consignee', 'Copy for carrier', 'Copy for the file'];

export function cmrPdf(id: string, copiesParam: string | undefined, crew: CrewLookup): MockReply & { headers?: Record<string, string> } {
  const c = find(id);
  if (!c) return notFound();
  const copies = Number(copiesParam ?? 1);
  if (!Number.isInteger(copies) || copies < 1 || copies > 4) return invalid({ copies: 'validation.invalid_value' });
  const n = c.status === 'DRAFT' ? copies : c.pdfCopies;
  const cmr = toCmr(c, crew);
  const party = (p: CmrParty | null) => (p ? [p.name, p.address?.replace(/\n/g, ', '), p.country, p.vatId].filter(Boolean).join(', ') : '');
  const body = [
    `CMR ${cmr.number}${c.status === 'DRAFT' ? '   DRAFT' : c.status === 'CANCELLED' ? '   CANCELLED' : ''}`,
    `1 Sender: ${party(cmr.sender)}`,
    `2 Consignee: ${party(cmr.consignee)}`,
    `3 Place of delivery: ${[cmr.deliveryPlace, cmr.deliveryCountry].filter(Boolean).join(', ')}`,
    `4 Place and date of taking over: ${[cmr.loadingPlace, cmr.loadingCountry, cmr.loadingDate].filter(Boolean).join(', ')}`,
    ...cmr.lines.map((l, i) => `6-12 Goods ${i + 1}: ${[l.marks, l.packages, l.packing, l.nature, l.statNumber, l.grossWeightKg && `${l.grossWeightKg} kg`, l.volumeM3 && `${l.volumeM3} m3`].filter(Boolean).join(' | ')}`),
    `16 Carrier: ${party(cmr.carrier)}`,
    `21 Established in: ${[cmr.establishedPlace, cmr.establishedDate].filter(Boolean).join(', ')}`,
    `Plates: ${cmr.vehiclePlates ?? ''}`,
  ];
  const pages = Array.from({ length: n }, (_, i) => [`${i + 1}. ${COPY_NAMES[i]}`, '', ...body]);
  return {
    status: 200,
    body: sketchPdf(pages),
    headers: { 'Content-Type': 'application/pdf', 'Content-Disposition': `inline; filename*=UTF-8''${encodeURIComponent(`${c.number.replace(/\//g, '-')}.pdf`)}` },
  };
}
