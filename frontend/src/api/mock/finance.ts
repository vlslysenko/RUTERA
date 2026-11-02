import type {
  DebtRow,
  Invoice,
  InvoiceDetail,
  InvoiceInput,
  InvoiceLine,
  InvoicePayment,
  InvoicePaymentInput,
  InvoiceStatus,
  Reminder,
  ReminderSchedule,
} from '@/api/finance';
import { lineAmounts, PAYMENT_METHODS, VAT_CODES } from '@/api/finance';
import { contractorForInvoice, orderForInvoice, setOrdersInvoiced, tripForInvoice } from './trips';

/*
 * Invoices, payments, debts and reminders for the in-browser mock API, with the rules of InvoiceService and
 * ReminderService: totals from the lines, computed status, FV/yyyy/MM/NNN for sales without a number, orders on an
 * invoice are INVOICED, no overpayment, debts in aging buckets from the sales invoices still open.
 */

export interface MockReply {
  status: number;
  body?: unknown;
}

const fail = (status: number, code: string, details?: Record<string, string>): MockReply => ({ status, body: details ? { code, details } : { code } });
const invalid = (details: Record<string, string>) => fail(422, 'error.validation', details);
const notFound = () => fail(404, 'error.not_found');
const blank = (s: string | null | undefined) => (s ?? '').trim() || null;
const r2 = (v: number) => Math.round((v + Number.EPSILON) * 100) / 100;
const CURRENCY_RE = /^[A-Z]{3}$/;
const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

function ymd(d: Date): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}
const today = () => ymd(new Date());
function addDays(day: string, n: number): string {
  const [y, m, d] = day.split('-').map(Number);
  return ymd(new Date(y, m - 1, d + n));
}
const inDays = (n: number) => addDays(today(), n);
const daysBetween = (a: string, b: string) => Math.round((Date.parse(`${b}T00:00:00Z`) - Date.parse(`${a}T00:00:00Z`)) / 86_400_000);

interface StoredInvoice extends Omit<InvoiceDetail, 'contractor' | 'orders' | 'trip' | 'status' | 'paidAmount' | 'openAmount' | 'daysOverdue' | 'reminders'> {
  contractorId: number;
  orderIds: number[];
  tripId: number | null;
  cancelled: boolean;
  reminders: Reminder[];
}

let nextId = 300;
const T0 = '2026-10-01T08:00:00Z';

function line(seq: number, description: string, qty: number, unitPrice: number, vatCode: InvoiceLine['vatCode'] = 'NP', vatRate: number | null = null, unit: string | null = 'usł.'): InvoiceLine {
  return { id: ++nextId, seq, description, qty, unit, unitPrice, vatCode, vatRate, ...lineAmounts({ qty, unitPrice, vatCode, vatRate }) };
}

function invoice(p: Partial<StoredInvoice> & Pick<StoredInvoice, 'number' | 'contractorId' | 'issueDate' | 'dueDate' | 'currency' | 'lines'>): StoredInvoice {
  const net = r2(p.lines.reduce((s, l) => s + l.net, 0));
  const vat = r2(p.lines.reduce((s, l) => s + l.vat, 0));
  return {
    id: ++nextId,
    direction: 'SALES',
    net,
    vat,
    gross: r2(net + vat),
    orderIds: [],
    tripId: null,
    remindersOn: true,
    lastReminderAt: null,
    source: 'MANUAL',
    notes: null,
    cancelled: false,
    payments: [],
    reminders: [],
    createdAt: T0,
    updatedAt: T0,
    ...p,
  };
}

const payment = (paidAt: string, amount: number, currency: string): InvoicePayment => ({
  id: ++nextId, paidAt, amount, currency, method: 'TRANSFER', reference: null, note: null, createdAt: T0,
});

const ym = (n: number) => {
  const d = new Date();
  d.setMonth(d.getMonth() + n);
  return `${d.getFullYear()}/${String(d.getMonth() + 1).padStart(2, '0')}`;
};

const invoices: StoredInvoice[] = [
  invoice({ number: `FV/${ym(-1)}/001`, contractorId: 1, issueDate: inDays(-12), dueDate: inDays(18), currency: 'EUR', orderIds: [1], tripId: 1, lines: [line(1, 'Transport Wrocław – Lyon, zlecenie SP/4471', 1, 4200)], payments: [] }),
  invoice({ number: `FV/${ym(-1)}/002`, contractorId: 2, issueDate: inDays(-40), dueDate: inDays(5), currency: 'EUR', lines: [line(1, 'Transport Bremen – Poznań', 1, 3150), line(2, 'Postój ponad normę', 3, 60, 'NP', null, 'h')], payments: [] }),
  invoice({ number: `FV/${ym(-2)}/004`, contractorId: 3, issueDate: inDays(-95), dueDate: inDays(-35), currency: 'EUR', lines: [line(1, 'Transport Valencia – Wrocław', 1, 3600)], notes: 'Klient prosi o kopię CMR.' }),
  invoice({ number: `FV/${ym(-3)}/007`, contractorId: 1, issueDate: inDays(-130), dueDate: inDays(-100), currency: 'PLN', lines: [line(1, 'Transport krajowy Kąty Wr. – Gdańsk', 1, 9800, 'RATE', 23)] }),
  invoice({ number: `FV/${ym(-2)}/002`, contractorId: 2, issueDate: inDays(-70), dueDate: inDays(-25), currency: 'EUR', lines: [line(1, 'Transport Hamburg – Praha', 1, 2900)] }),
  invoice({ direction: 'PURCHASE', number: 'TK/2026/118', contractorId: 4, issueDate: inDays(-8), dueDate: inDays(6), currency: 'PLN', remindersOn: false, lines: [line(1, 'Podwykonawstwo: Poznań – Berlin', 1, 3500, 'RATE', 23)] }),
];
// Paid, partly paid and reminded examples.
invoices[3].payments.push(payment(inDays(-98), invoices[3].gross, 'PLN'));
invoices[4].payments.push(payment(inDays(-20), 1500, 'EUR'));
invoices[2].reminders.push(
  { id: ++nextId, offsetDays: 1, sentAt: `${inDays(-34)}T08:00:00Z`, toEmails: ['ap@iberia.example'], subject: `Payment reminder: invoice ${invoices[2].number}`, status: 'SENT', error: null, createdAt: T0 },
  { id: ++nextId, offsetDays: 30, sentAt: null, toEmails: ['ap@iberia.example'], subject: `Payment reminder: invoice ${invoices[2].number}`, status: 'QUEUED', error: null, createdAt: T0 },
);
invoices[2].lastReminderAt = `${inDays(-34)}T08:00:00Z`;
setOrdersInvoiced([1], true);

let schedule: ReminderSchedule = {
  sendHour: 8,
  ccEmails: [],
  stopWhenPaid: true,
  steps: [
    { offsetDays: -3, escalate: false },
    { offsetDays: 1, escalate: false },
    { offsetDays: 7, escalate: false },
    { offsetDays: 14, escalate: true },
    { offsetDays: 30, escalate: true },
  ],
};

// --- view --------------------------------------------------------------------------------------------

function computed(i: StoredInvoice) {
  const paid = r2(i.payments.reduce((s, p) => s + p.amount, 0));
  const open = i.cancelled ? 0 : Math.max(0, r2(i.gross - paid));
  const overdueDays = Math.max(0, daysBetween(i.dueDate, today()));
  let status: InvoiceStatus;
  if (i.cancelled) status = 'CANCELLED';
  else if (paid >= i.gross) status = 'PAID';
  else if (i.dueDate < today()) status = 'OVERDUE';
  else status = paid > 0 ? 'PARTIAL' : 'OPEN';
  return { paid, open, status, daysOverdue: status === 'OVERDUE' ? overdueDays : 0 };
}

function toInvoice(i: StoredInvoice): Invoice {
  const c = computed(i);
  const contractor = contractorForInvoice(i.contractorId);
  const trip = i.tripId == null ? undefined : tripForInvoice(i.tripId);
  return {
    id: i.id, direction: i.direction, number: i.number, contractor: { id: i.contractorId, name: contractor?.name ?? '?' }, issueDate: i.issueDate,
    dueDate: i.dueDate, currency: i.currency, net: i.net, vat: i.vat, gross: i.gross, paidAmount: c.paid, openAmount: c.open, status: c.status,
    daysOverdue: c.daysOverdue, orders: i.orderIds.map((id) => ({ id, number: orderForInvoice(id)?.number ?? '?' })), trip: trip ?? null,
    remindersOn: i.remindersOn, lastReminderAt: i.lastReminderAt, source: 'MANUAL', notes: i.notes, createdAt: i.createdAt, updatedAt: i.updatedAt,
  };
}

const toDetail = (i: StoredInvoice): InvoiceDetail => ({ ...toInvoice(i), lines: i.lines, payments: i.payments, reminders: i.reminders });

// --- invoices ----------------------------------------------------------------------------------------

export function listInvoices(query: Record<string, string>): MockReply {
  const needle = (query.q ?? '').trim().toLowerCase();
  const all = invoices
    .filter((i) => !query.direction || i.direction === query.direction)
    .filter((i) => !query.contractorId || String(i.contractorId) === query.contractorId)
    .filter((i) => !query.from || i.issueDate >= query.from)
    .filter((i) => !query.to || i.issueDate <= query.to)
    .map(toInvoice)
    .filter((i) => !query.status || i.status === query.status)
    .filter((i) => query.overdue !== 'true' || i.status === 'OVERDUE')
    .filter((i) => !needle || i.number.toLowerCase().includes(needle) || i.contractor.name.toLowerCase().includes(needle))
    .sort((a, b) => b.issueDate.localeCompare(a.issueDate) || b.id - a.id);
  const size = Math.min(Math.max(Number(query.size ?? 50) || 50, 1), 200);
  const from = (Number(query.page ?? 0) || 0) * size;
  const sums = new Map<string, { gross: number; open: number }>();
  for (const i of all.filter((x) => x.status !== 'CANCELLED')) {
    const s = sums.get(i.currency) ?? { gross: 0, open: 0 };
    sums.set(i.currency, { gross: r2(s.gross + i.gross), open: r2(s.open + i.openAmount) });
  }
  return { status: 200, body: { items: all.slice(from, from + size), total: all.length, totals: [...sums].map(([currency, s]) => ({ currency, ...s })) } };
}

export function getInvoice(id: string): MockReply {
  const i = invoices.find((x) => String(x.id) === id);
  return i ? { status: 200, body: toDetail(i) } : notFound();
}

export function saveInvoice(b: InvoiceInput, id: string | null): MockReply {
  const target = id == null ? undefined : invoices.find((x) => String(x.id) === id);
  if (id != null && !target) return notFound();
  const errors: Record<string, string> = {};
  const direction = b.direction ?? 'SALES';
  const number = blank(b.number);
  if (!number && direction === 'PURCHASE') errors.number = 'validation.required';
  if (number && invoices.some((i) => i.direction === direction && i.number.toUpperCase() === number.toUpperCase() && i.id !== target?.id)) {
    errors.number = 'finance.invoice.number_taken';
  }
  const contractor = b.contractorId == null ? undefined : contractorForInvoice(b.contractorId);
  if (b.contractorId == null) errors.contractorId = 'validation.required';
  else if (!contractor) errors.contractorId = 'validation.invalid_value';
  if (!b.issueDate) errors.issueDate = 'validation.required';
  if (b.issueDate && b.dueDate && b.dueDate < b.issueDate) errors.dueDate = 'validation.invalid_value';
  const currency = (b.currency ?? '').trim().toUpperCase();
  if (!currency) errors.currency = 'validation.required';
  else if (!CURRENCY_RE.test(currency)) errors.currency = 'validation.invalid_format';
  if (b.tripId != null && !tripForInvoice(b.tripId)) errors.tripId = 'validation.invalid_value';
  // Only a sales invoice bills orders.
  const orderIds = direction === 'SALES' ? (b.orderIds ?? []) : [];
  if (orderIds.some((oid) => {
    const o = orderForInvoice(oid);
    return !o || o.customerId !== b.contractorId || o.status === 'CANCELLED' || invoices.some((i) => i.id !== target?.id && !i.cancelled && i.orderIds.includes(oid));
  })) errors.orderIds = 'validation.invalid_value';

  const lines: InvoiceLine[] = [];
  (b.lines ?? []).forEach((l, n) => {
    const at = `lines[${n}].`;
    if (!blank(l.description)) errors[`${at}description`] = 'validation.required';
    if (l.qty == null) errors[`${at}qty`] = 'validation.required';
    else if (l.qty < 0.001) errors[`${at}qty`] = 'validation.invalid_value';
    if (l.unitPrice == null) errors[`${at}unitPrice`] = 'validation.required';
    const code = l.vatCode ?? 'RATE';
    if (!VAT_CODES.includes(code)) errors[`${at}vatCode`] = 'validation.invalid_value';
    if (code === 'RATE' && l.vatRate == null) errors[`${at}vatRate`] = 'validation.required';
    if (blank(l.description) && l.qty != null && l.unitPrice != null && (code !== 'RATE' || l.vatRate != null)) {
      const vatRate = code === 'RATE' ? l.vatRate : null;
      lines.push({ id: ++nextId, seq: n + 1, description: l.description.trim(), qty: l.qty, unit: blank(l.unit), unitPrice: l.unitPrice, vatCode: code, vatRate, ...lineAmounts({ ...l, vatCode: code, vatRate }) });
    }
  });
  let net = 0;
  let vat = 0;
  let gross = 0;
  if ((b.lines ?? []).length > 0) {
    net = r2(lines.reduce((s, l) => s + l.net, 0));
    vat = r2(lines.reduce((s, l) => s + l.vat, 0));
    gross = r2(net + vat);
  } else {
    if (b.net == null) errors.net = 'validation.required';
    if (b.gross == null) errors.gross = 'validation.required';
    if (b.net != null && b.gross != null) {
      net = r2(b.net);
      gross = r2(b.gross);
      vat = b.vat == null ? r2(gross - net) : r2(b.vat);
      if (r2(net + vat) !== gross) errors.gross = 'validation.invalid_value';
    }
  }
  if ((b.notes ?? '').length > 2000) errors.notes = 'validation.too_long';
  if (Object.keys(errors).length) return invalid(errors);

  const now = new Date().toISOString();
  const issue = b.issueDate;
  const autoNumber = () => {
    const prefix = `FV/${issue.slice(0, 4)}/${issue.slice(5, 7)}/`;
    const n = Math.max(0, ...invoices.filter((i) => i.direction === 'SALES' && i.number.startsWith(prefix)).map((i) => Number(i.number.slice(prefix.length)) || 0)) + 1;
    return `${prefix}${String(n).padStart(3, '0')}`;
  };
  const previousOrders = target?.orderIds ?? [];
  const value = {
    direction,
    number: number ?? target?.number ?? autoNumber(),
    contractorId: b.contractorId,
    issueDate: issue,
    dueDate: b.dueDate ?? addDays(issue, contractor?.paymentDays ?? 14),
    currency,
    lines,
    net,
    vat,
    gross,
    orderIds: b.cancelled ? [] : orderIds,
    tripId: b.tripId,
    remindersOn: b.remindersOn ?? direction === 'SALES',
    cancelled: !!b.cancelled,
    notes: blank(b.notes),
    updatedAt: now,
  };
  let saved: StoredInvoice;
  if (target) {
    Object.assign(target, value);
    saved = target;
  } else {
    saved = { ...value, id: ++nextId, lastReminderAt: null, source: 'MANUAL', payments: [], reminders: [], createdAt: now };
    invoices.push(saved);
  }
  setOrdersInvoiced(previousOrders.filter((oid) => !saved.orderIds.includes(oid)), false);
  setOrdersInvoiced(saved.orderIds, true);
  return { status: target ? 200 : 201, body: toDetail(saved) };
}

export function removeInvoice(id: string): MockReply {
  const i = invoices.findIndex((x) => String(x.id) === id);
  if (i < 0) return notFound();
  if (invoices[i].payments.length > 0) return fail(409, 'error.conflict');
  setOrdersInvoiced(invoices[i].orderIds, false);
  invoices.splice(i, 1);
  return { status: 204 };
}

// --- payments ----------------------------------------------------------------------------------------

export function addPayment(id: string, b: InvoicePaymentInput): MockReply {
  const i = invoices.find((x) => String(x.id) === id);
  if (!i) return notFound();
  if (i.cancelled) return fail(409, 'error.conflict');
  const errors: Record<string, string> = {};
  if (!b.paidAt) errors.paidAt = 'validation.required';
  if (b.amount == null) errors.amount = 'validation.required';
  else if (b.amount < 0.01) errors.amount = 'validation.invalid_value';
  else if (r2(computed(i).paid + b.amount) > i.gross) errors.amount = 'finance.payment.too_much';
  if (b.method && !PAYMENT_METHODS.includes(b.method)) errors.method = 'validation.invalid_value';
  if (Object.keys(errors).length) return invalid(errors);
  const p: InvoicePayment = { id: ++nextId, paidAt: b.paidAt, amount: r2(b.amount), currency: i.currency, method: b.method ?? 'TRANSFER', reference: blank(b.reference), note: blank(b.note), createdAt: new Date().toISOString() };
  i.payments.push(p);
  return { status: 201, body: p };
}

export function removePayment(id: string, paymentId: string): MockReply {
  const i = invoices.find((x) => String(x.id) === id);
  const n = i ? i.payments.findIndex((p) => String(p.id) === paymentId) : -1;
  if (!i || n < 0) return notFound();
  i.payments.splice(n, 1);
  return { status: 204 };
}

// --- reminders ---------------------------------------------------------------------------------------

export function listReminders(id: string): MockReply {
  const i = invoices.find((x) => String(x.id) === id);
  return i ? { status: 200, body: [...i.reminders].sort((a, b) => b.createdAt.localeCompare(a.createdAt)) } : notFound();
}

export function sendReminder(id: string): MockReply {
  const i = invoices.find((x) => String(x.id) === id);
  if (!i) return notFound();
  // Only an open sales invoice is reminded.
  if (i.direction !== 'SALES' || computed(i).open <= 0) return fail(409, 'error.conflict');
  const emails = contractorForInvoice(i.contractorId)?.invoiceEmails ?? [];
  if (emails.length === 0) return invalid({ contractor: 'finance.reminder.no_email' });
  const now = new Date().toISOString();
  // No mail server in the mock: the reminder stays queued, as on a backend without RUTERA_MAIL_*.
  const r: Reminder = { id: ++nextId, offsetDays: null, sentAt: null, toEmails: [...emails, ...schedule.ccEmails], subject: `Payment reminder: invoice ${i.number}`, status: 'QUEUED', error: null, createdAt: now };
  i.reminders.push(r);
  return { status: 201, body: r };
}

export function getSchedule(): MockReply {
  return { status: 200, body: schedule };
}

export function saveSchedule(b: Partial<ReminderSchedule>): MockReply {
  const errors: Record<string, string> = {};
  if (b.sendHour != null && (b.sendHour < 0 || b.sendHour > 23)) errors.sendHour = 'validation.invalid_value';
  const cc = [...new Set((b.ccEmails ?? []).map((e) => e.trim()).filter(Boolean))];
  if (cc.length > 5) errors.ccEmails = 'validation.too_long';
  else if (cc.some((e) => !EMAIL_RE.test(e))) errors.ccEmails = 'validation.invalid_format';
  const offsets = new Set<number>();
  for (const s of b.steps ?? []) {
    if (s.offsetDays == null || s.offsetDays < -60 || s.offsetDays > 365 || offsets.has(s.offsetDays)) errors.steps = 'validation.invalid_value';
    else offsets.add(s.offsetDays);
  }
  if ((b.steps ?? []).length > 10) errors.steps = 'validation.too_long';
  if (Object.keys(errors).length) return invalid(errors);
  schedule = {
    sendHour: b.sendHour ?? schedule.sendHour,
    ccEmails: cc,
    stopWhenPaid: b.stopWhenPaid ?? true,
    steps: (b.steps ?? schedule.steps).map((s) => ({ offsetDays: s.offsetDays, escalate: !!s.escalate })).sort((x, y) => x.offsetDays - y.offsetDays),
  };
  return { status: 200, body: schedule };
}

// --- debts -------------------------------------------------------------------------------------------

export function debts(query: Record<string, string>): MockReply {
  const asOf = query.asOf || today();
  const rows = new Map<string, DebtRow>();
  for (const i of invoices.filter((x) => x.direction === 'SALES' && !x.cancelled && x.issueDate <= asOf)) {
    const paid = r2(i.payments.filter((p) => p.paidAt <= asOf).reduce((s, p) => s + p.amount, 0));
    const open = r2(i.gross - paid);
    if (open <= 0) continue;
    const late = daysBetween(i.dueDate, asOf);
    const key = `${i.contractorId}|${i.currency}`;
    const row = rows.get(key) ?? {
      contractor: { id: i.contractorId, name: contractorForInvoice(i.contractorId)?.name ?? '?' },
      currency: i.currency,
      open: 0,
      overdue: 0,
      invoices: 0,
      oldestDueDate: null,
      maxDaysOverdue: 0,
      buckets: { notDue: 0, d1to30: 0, d31to60: 0, d61to90: 0, over90: 0 },
    };
    row.open = r2(row.open + open);
    row.invoices += 1;
    if (late > 0) row.overdue = r2(row.overdue + open);
    row.maxDaysOverdue = Math.max(row.maxDaysOverdue, Math.max(0, late));
    if (!row.oldestDueDate || i.dueDate < row.oldestDueDate) row.oldestDueDate = i.dueDate;
    const b = row.buckets;
    if (late <= 0) b.notDue = r2(b.notDue + open);
    else if (late <= 30) b.d1to30 = r2(b.d1to30 + open);
    else if (late <= 60) b.d31to60 = r2(b.d31to60 + open);
    else if (late <= 90) b.d61to90 = r2(b.d61to90 + open);
    else b.over90 = r2(b.over90 + open);
    rows.set(key, row);
  }
  return { status: 200, body: [...rows.values()].sort((a, b) => b.overdue - a.overdue || b.open - a.open) };
}

