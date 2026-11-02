import type {
  Absence,
  AbsenceInput,
  AbsenceKind,
  DayKind,
  DriverDay,
  DriverMonth,
  DriverPayment,
  DriverPaymentInput,
  PayRate,
  PayRateInput,
  PaymentKind,
  PersonRef,
} from '@/api/hr';
import { ABSENCE_KINDS, DAY_KINDS, NEGATIVE_PAYMENT_KINDS, PAYMENT_KINDS } from '@/api/hr';

/* Driver HR for the in-browser mock API, with the rules of DriverHrService (absences may not overlap, a manual day
 * wins over an absence, which wins over the tachograph; payments count advances and deductions negative). */

export interface MockReply {
  status: number;
  body?: unknown;
}

export interface HrLookup {
  driverName(id: number): string | null;
}

const fail = (status: number, code: string, details?: Record<string, string>): MockReply => ({ status, body: details ? { code, details } : { code } });
const invalid = (details: Record<string, string>) => fail(422, 'error.validation', details);
const notFound = () => fail(404, 'error.not_found');
const T0 = '2026-10-01T08:00:00Z';
const CURRENCY_RE = /^[A-Z]{3}$/;
const MONTH_RE = /^\d{4}-(0[1-9]|1[0-2])$/;

function ymd(d: Date): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}
function inDays(n: number): string {
  const d = new Date();
  d.setDate(d.getDate() + n);
  return ymd(d);
}
const utc = (s: string) => {
  const [y, m, d] = s.split('-').map(Number);
  return Date.UTC(y, m - 1, d);
};
const dayCount = (from: string, to: string) => Math.round((utc(to) - utc(from)) / 86_400_000) + 1;
const weekday = (s: string) => (new Date(utc(s)).getUTCDay() + 6) % 7;
function workingDays(from: string, to: string): number {
  let n = 0;
  for (let t = utc(from); t <= utc(to); t += 86_400_000) if ((new Date(t).getUTCDay() + 6) % 7 < 5) n++;
  return n;
}
const blank = (s: string | null | undefined) => (s ?? '').trim() || null;
const thisMonth = () => inDays(0).slice(0, 7);
const lastMonth = () => {
  const d = new Date();
  d.setMonth(d.getMonth() - 1);
  return ymd(d).slice(0, 7);
};

interface StoredAbsence extends Omit<Absence, 'driver' | 'days' | 'workingDays' | 'approvedBy'> {
  driverId: number;
  approvedBy: PersonRef | null;
}

const MANAGER: PersonRef = { id: 1, name: 'Anna Kowalska' };
let nextId = 100;

const absences: StoredAbsence[] = [
  { id: ++nextId, driverId: 2, kind: 'VACATION', fromDate: inDays(3), toDate: inDays(9), paid: true, note: 'Urlop wypoczynkowy', approvedBy: MANAGER, approvedAt: T0, createdAt: T0, updatedAt: T0 },
  { id: ++nextId, driverId: 4, kind: 'SICK', fromDate: inDays(-4), toDate: inDays(1), paid: true, note: 'L4', approvedBy: MANAGER, approvedAt: T0, createdAt: T0, updatedAt: T0 },
  { id: ++nextId, driverId: 3, kind: 'UNPAID', fromDate: inDays(12), toDate: inDays(13), paid: false, note: null, approvedBy: MANAGER, approvedAt: T0, createdAt: T0, updatedAt: T0 },
  { id: ++nextId, driverId: 1, kind: 'VACATION', fromDate: inDays(-40), toDate: inDays(-30), paid: true, note: null, approvedBy: MANAGER, approvedAt: T0, createdAt: T0, updatedAt: T0 },
];

/** Manual days: driverId -> day -> entry. */
const manualDays = new Map<number, Map<string, { kind: DayKind; note: string | null }>>();

interface StoredRate extends PayRate {
  driverId: number;
}
const rates: StoredRate[] = [
  { id: ++nextId, driverId: 1, validFrom: '2026-01-01', monthlyBase: 4666, perDiemHome: 45, perDiemAbroad: 60, perKm: null, currency: 'PLN', createdAt: T0, updatedAt: T0 },
  { id: ++nextId, driverId: 1, validFrom: '2025-01-01', monthlyBase: 4300, perDiemHome: 45, perDiemAbroad: 55, perKm: null, currency: 'PLN', createdAt: T0, updatedAt: T0 },
  { id: ++nextId, driverId: 2, validFrom: '2026-03-01', monthlyBase: 4666, perDiemHome: null, perDiemAbroad: 60, perKm: 0.12, currency: 'PLN', createdAt: T0, updatedAt: T0 },
];

interface StoredPayment extends Omit<DriverPayment, 'driver'> {
  driverId: number;
}
const payment = (driverId: number, period: string, kind: PaymentKind, amount: number, paidAt: string | null, note: string | null = null): StoredPayment => ({
  id: ++nextId, driverId, period, kind, amount, currency: kind === 'PER_DIEM' ? 'EUR' : 'PLN', paidAt, note, createdAt: T0, updatedAt: T0,
});
const payments: StoredPayment[] = [
  payment(1, lastMonth(), 'SALARY', 4666, `${thisMonth()}-10`),
  payment(1, lastMonth(), 'PER_DIEM', 1320, `${thisMonth()}-10`),
  payment(1, thisMonth(), 'ADVANCE', 500, inDays(-1), 'Zaliczka na paliwo'),
  payment(2, lastMonth(), 'SALARY', 4666, `${thisMonth()}-10`),
  payment(2, lastMonth(), 'BONUS', 300, `${thisMonth()}-10`, 'Premia za brak szkód'),
  payment(3, lastMonth(), 'SALARY', 4666, null),
  payment(3, lastMonth(), 'DEDUCTION', 150, null, 'Mandat'),
];

// --- absences ----------------------------------------------------------------------------------------

function toAbsence(a: StoredAbsence, hr: HrLookup): Absence {
  const { driverId, ...rest } = a;
  return { ...rest, driver: { id: driverId, name: hr.driverName(driverId) ?? '?' }, days: dayCount(a.fromDate, a.toDate), workingDays: workingDays(a.fromDate, a.toDate) };
}

export function listAbsences(driverId: number, query: Record<string, string>, hr: HrLookup): MockReply {
  const items = absences
    .filter((a) => a.driverId === driverId)
    .filter((a) => (!query.to || a.fromDate <= query.to) && (!query.from || a.toDate >= query.from))
    .sort((a, b) => b.fromDate.localeCompare(a.fromDate));
  return { status: 200, body: items.map((a) => toAbsence(a, hr)) };
}

export function firmAbsences(query: Record<string, string>, hr: HrLookup): MockReply {
  const items = absences
    .filter((a) => (!query.to || a.fromDate <= query.to) && (!query.from || a.toDate >= query.from))
    .filter((a) => !query.kind || a.kind === query.kind)
    .filter((a) => !query.driverId || String(a.driverId) === query.driverId)
    .filter((a) => hr.driverName(a.driverId) !== null)
    .sort((a, b) => a.fromDate.localeCompare(b.fromDate));
  return { status: 200, body: items.map((a) => toAbsence(a, hr)) };
}

export function saveAbsence(driverId: number, b: AbsenceInput, id: string | null, hr: HrLookup, approver: PersonRef): MockReply {
  const target = id == null ? undefined : absences.find((a) => String(a.id) === id && a.driverId === driverId);
  if (id != null && !target) return notFound();
  const errors: Record<string, string> = {};
  if (!ABSENCE_KINDS.includes(b.kind)) errors.kind = b.kind ? 'validation.invalid_value' : 'validation.required';
  if (!b.fromDate) errors.fromDate = 'validation.required';
  if (!b.toDate) errors.toDate = 'validation.required';
  else if (b.fromDate) {
    if (b.toDate < b.fromDate || dayCount(b.fromDate, b.toDate) > 366) errors.toDate = 'validation.invalid_value';
    else if (absences.some((a) => a.driverId === driverId && a.id !== target?.id && a.fromDate <= b.toDate && a.toDate >= b.fromDate)) {
      errors.toDate = 'driver.absence.overlap';
    }
  }
  if ((b.note ?? '').length > 500) errors.note = 'validation.too_long';
  if (Object.keys(errors).length) return invalid(errors);
  const now = new Date().toISOString();
  const value = { kind: b.kind as AbsenceKind, fromDate: b.fromDate, toDate: b.toDate, paid: b.paid ?? b.kind !== 'UNPAID', note: blank(b.note), updatedAt: now };
  if (target) {
    Object.assign(target, value);
    return { status: 200, body: toAbsence(target, hr) };
  }
  const created: StoredAbsence = { ...value, id: ++nextId, driverId, approvedBy: approver, approvedAt: now, createdAt: now };
  absences.push(created);
  return { status: 201, body: toAbsence(created, hr) };
}

export function removeAbsence(driverId: number, id: string): MockReply {
  const i = absences.findIndex((a) => String(a.id) === id && a.driverId === driverId);
  if (i < 0) return notFound();
  absences.splice(i, 1);
  return { status: 204 };
}

// --- work days ---------------------------------------------------------------------------------------

/** What the tachograph "recorded": weekdays in the past are work with some driving, the rest are rest. */
function tachoDay(driverId: number, day: string): { drivingMin: number; workMin: number } | null {
  if (day > inDays(0)) return null;
  if (weekday(day) >= 5) return { drivingMin: 0, workMin: 0 };
  const seed = (utc(day) / 86_400_000 + driverId * 7) % 11;
  return { drivingMin: 300 + seed * 25, workMin: 45 + seed * 5 };
}

function computeDay(driverId: number, day: string): DriverDay {
  const manual = manualDays.get(driverId)?.get(day);
  const tacho = tachoDay(driverId, day);
  const absence = absences.find((a) => a.driverId === driverId && a.fromDate <= day && day <= a.toDate);
  const minutes = { drivingMin: tacho?.drivingMin ?? 0, workMin: tacho?.workMin ?? 0 };
  if (manual) return { day, kind: manual.kind, source: 'MANUAL', absenceKind: manual.kind === 'ABSENCE' ? (absence?.kind ?? null) : null, ...minutes, note: manual.note };
  if (absence) return { day, kind: 'ABSENCE', source: 'ABSENCE', absenceKind: absence.kind, ...minutes, note: absence.note };
  if (!tacho) return { day, kind: 'REST', source: 'NONE', absenceKind: null, drivingMin: 0, workMin: 0, note: null };
  return { day, kind: tacho.drivingMin + tacho.workMin > 0 ? 'WORK' : 'REST', source: 'TACHO', absenceKind: null, ...minutes, note: null };
}

export function month(driverId: number, query: Record<string, string>): MockReply {
  const m = query.month ?? '';
  if (!m) return invalid({ month: 'validation.required' });
  if (!MONTH_RE.test(m)) return invalid({ month: 'validation.invalid_format' });
  const [y, mm] = m.split('-').map(Number);
  const n = new Date(Date.UTC(y, mm, 0)).getUTCDate();
  const days = Array.from({ length: n }, (_, i) => computeDay(driverId, `${m}-${String(i + 1).padStart(2, '0')}`));
  const count = (k: DayKind) => days.filter((d) => d.kind === k).length;
  const body: DriverMonth = {
    days,
    summary: {
      workDays: count('WORK'),
      restDays: count('REST'),
      absenceDays: count('ABSENCE'),
      holidayDays: count('HOLIDAY'),
      drivingMin: days.reduce((s, d) => s + d.drivingMin, 0),
      workMin: days.reduce((s, d) => s + d.workMin, 0),
    },
  };
  return { status: 200, body };
}

export function setDay(driverId: number, day: string, b: { kind?: string; note?: string | null }): MockReply {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(day)) return invalid({ day: 'validation.invalid_format' });
  if (!b.kind || !DAY_KINDS.includes(b.kind as DayKind)) return invalid({ kind: b.kind ? 'validation.invalid_value' : 'validation.required' });
  if ((b.note ?? '').length > 500) return invalid({ note: 'validation.too_long' });
  if (!manualDays.has(driverId)) manualDays.set(driverId, new Map());
  manualDays.get(driverId)!.set(day, { kind: b.kind as DayKind, note: blank(b.note) });
  return { status: 200, body: computeDay(driverId, day) };
}

export function resetDay(driverId: number, day: string): MockReply {
  manualDays.get(driverId)?.delete(day);
  return { status: 200, body: computeDay(driverId, day) };
}

// --- pay rates ---------------------------------------------------------------------------------------

export function listRates(driverId: number): MockReply {
  const items = rates.filter((r) => r.driverId === driverId).sort((a, b) => b.validFrom.localeCompare(a.validFrom));
  return { status: 200, body: items.map(({ driverId: _d, ...r }) => r) };
}

export function saveRate(driverId: number, b: PayRateInput, id: string | null): MockReply {
  const target = id == null ? undefined : rates.find((r) => String(r.id) === id && r.driverId === driverId);
  if (id != null && !target) return notFound();
  const errors: Record<string, string> = {};
  if (!b.validFrom) errors.validFrom = 'validation.required';
  else if (rates.some((r) => r.driverId === driverId && r.validFrom === b.validFrom && r.id !== target?.id)) errors.validFrom = 'driver.pay_rate.date_taken';
  if (b.monthlyBase == null && b.perDiemHome == null && b.perDiemAbroad == null && b.perKm == null) errors.monthlyBase = 'validation.required';
  const currency = (b.currency ?? '').trim().toUpperCase();
  if (!currency) errors.currency = 'validation.required';
  else if (!CURRENCY_RE.test(currency)) errors.currency = 'validation.invalid_format';
  if (Object.keys(errors).length) return invalid(errors);
  const now = new Date().toISOString();
  const value = { validFrom: b.validFrom, monthlyBase: b.monthlyBase, perDiemHome: b.perDiemHome, perDiemAbroad: b.perDiemAbroad, perKm: b.perKm, currency, updatedAt: now };
  if (target) {
    Object.assign(target, value);
    const { driverId: _d, ...r } = target;
    return { status: 200, body: r };
  }
  const created: StoredRate = { ...value, id: ++nextId, driverId, createdAt: now };
  rates.push(created);
  const { driverId: _d, ...r } = created;
  return { status: 201, body: r };
}

export function removeRate(driverId: number, id: string): MockReply {
  const i = rates.findIndex((r) => String(r.id) === id && r.driverId === driverId);
  if (i < 0) return notFound();
  rates.splice(i, 1);
  return { status: 204 };
}

// --- payments ----------------------------------------------------------------------------------------

function toPayment(p: StoredPayment, hr: HrLookup): DriverPayment {
  const { driverId, ...rest } = p;
  return { ...rest, driver: { id: driverId, name: hr.driverName(driverId) ?? '?' } };
}

export function listPayments(driverId: number, query: Record<string, string>, hr: HrLookup): MockReply {
  for (const f of ['from', 'to'] as const) if (query[f] && !MONTH_RE.test(query[f])) return invalid({ [f]: 'validation.invalid_format' });
  const items = payments
    .filter((p) => p.driverId === driverId)
    .filter((p) => (!query.from || p.period >= query.from) && (!query.to || p.period <= query.to))
    .sort((a, b) => b.period.localeCompare(a.period) || b.id - a.id);
  return { status: 200, body: items.map((p) => toPayment(p, hr)) };
}

export function firmPayments(query: Record<string, string>, hr: HrLookup): MockReply {
  if (query.period && !MONTH_RE.test(query.period)) return invalid({ period: 'validation.invalid_format' });
  const items = payments
    .filter((p) => !query.period || p.period === query.period)
    .filter((p) => !query.driverId || String(p.driverId) === query.driverId)
    .filter((p) => !query.kind || p.kind === query.kind)
    .sort((a, b) => (hr.driverName(a.driverId) ?? '').localeCompare(hr.driverName(b.driverId) ?? '') || a.kind.localeCompare(b.kind));
  const sums = new Map<string, number>();
  for (const p of items) sums.set(p.currency, (sums.get(p.currency) ?? 0) + (NEGATIVE_PAYMENT_KINDS.includes(p.kind) ? -p.amount : p.amount));
  return {
    status: 200,
    body: { items: items.map((p) => toPayment(p, hr)), totals: [...sums].map(([currency, amount]) => ({ currency, amount: Math.round(amount * 100) / 100 })) },
  };
}

export function savePayment(driverId: number, b: DriverPaymentInput, id: string | null, hr: HrLookup): MockReply {
  const target = id == null ? undefined : payments.find((p) => String(p.id) === id && p.driverId === driverId);
  if (id != null && !target) return notFound();
  const errors: Record<string, string> = {};
  if (!b.period) errors.period = 'validation.required';
  else if (!MONTH_RE.test(b.period)) errors.period = 'validation.invalid_format';
  if (!PAYMENT_KINDS.includes(b.kind)) errors.kind = b.kind ? 'validation.invalid_value' : 'validation.required';
  if (b.amount == null) errors.amount = 'validation.required';
  else if (b.amount < 0.01) errors.amount = 'validation.invalid_value';
  const currency = (b.currency ?? '').trim().toUpperCase();
  if (!currency) errors.currency = 'validation.required';
  else if (!CURRENCY_RE.test(currency)) errors.currency = 'validation.invalid_format';
  if (Object.keys(errors).length) return invalid(errors);
  const now = new Date().toISOString();
  const value = { period: b.period, kind: b.kind, amount: Math.round(b.amount * 100) / 100, currency, paidAt: b.paidAt, note: blank(b.note), updatedAt: now };
  if (target) {
    Object.assign(target, value);
    return { status: 200, body: toPayment(target, hr) };
  }
  const created: StoredPayment = { ...value, id: ++nextId, driverId, createdAt: now };
  payments.push(created);
  return { status: 201, body: toPayment(created, hr) };
}

export function removePayment(driverId: number, id: string): MockReply {
  const i = payments.findIndex((p) => String(p.id) === id && p.driverId === driverId);
  if (i < 0) return notFound();
  payments.splice(i, 1);
  return { status: 204 };
}
