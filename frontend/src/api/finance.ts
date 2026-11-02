import { api } from './client';

/*
 * Invoices, payments, debts and payment reminders (backend contract "Invoices and debts", DB_SCHEMA group 6).
 * Privileges: finance.invoice/view, finance.invoice/manage, finance.payment/manage, finance.reminder/manage.
 * Money: numbers with 2 decimals (unit price 4, quantity 3); dates YYYY-MM-DD; instants ISO UTC.
 */

export interface Ref {
  id: number;
  name: string;
}

export interface NumberRef {
  id: number;
  number: string;
}

export const INVOICE_DIRECTIONS = ['SALES', 'PURCHASE'] as const;
export type InvoiceDirection = (typeof INVOICE_DIRECTIONS)[number];

/** Computed: CANCELLED, else PAID (paid >= gross), else OVERDUE (due date passed), else PARTIAL or OPEN. */
export const INVOICE_STATUSES = ['OPEN', 'PARTIAL', 'PAID', 'OVERDUE', 'CANCELLED'] as const;
export type InvoiceStatus = (typeof INVOICE_STATUSES)[number];

/** RATE = a VAT rate in percent; ZW exempt, NP not subject (e.g. international transport), OO reverse charge. */
export const VAT_CODES = ['RATE', 'ZW', 'NP', 'OO'] as const;
export type VatCode = (typeof VAT_CODES)[number];

export const PAYMENT_METHODS = ['TRANSFER', 'CASH', 'OFFSET'] as const;
export type PaymentMethod = (typeof PAYMENT_METHODS)[number];

export const REMINDER_STATUSES = ['QUEUED', 'SENT', 'FAILED'] as const;
export type ReminderStatus = (typeof REMINDER_STATUSES)[number];

export interface Invoice {
  id: number;
  direction: InvoiceDirection;
  number: string;
  contractor: Ref;
  issueDate: string;
  dueDate: string;
  currency: string;
  net: number;
  vat: number;
  gross: number;
  paidAmount: number;
  openAmount: number;
  status: InvoiceStatus;
  daysOverdue: number;
  orders: NumberRef[];
  trip: NumberRef | null;
  remindersOn: boolean;
  lastReminderAt: string | null;
  source: 'MANUAL';
  notes: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface InvoiceLine {
  id: number;
  seq: number;
  description: string;
  qty: number;
  unit: string | null;
  unitPrice: number;
  vatCode: VatCode;
  /** Percent; only for RATE. */
  vatRate: number | null;
  net: number;
  vat: number;
  gross: number;
}

export interface InvoicePayment {
  id: number;
  paidAt: string;
  amount: number;
  /** = the invoice currency */
  currency: string;
  method: PaymentMethod;
  reference: string | null;
  note: string | null;
  createdAt: string;
}

export interface Reminder {
  id: number;
  /** Step of the schedule (days from the due date); null for a reminder sent by hand. */
  offsetDays: number | null;
  sentAt: string | null;
  toEmails: string[];
  subject: string;
  status: ReminderStatus;
  error: string | null;
  createdAt: string;
}

export interface InvoiceDetail extends Invoice {
  lines: InvoiceLine[];
  payments: InvoicePayment[];
  reminders: Reminder[];
}

export interface InvoiceLineInput {
  description: string;
  qty: number;
  unit: string | null;
  unitPrice: number;
  vatCode: VatCode;
  vatRate: number | null;
}

/**
 * POST / PUT (full replacement). With lines the server computes net, VAT and gross (net = qty x unit price rounded
 * per line); without lines net and gross are required. An empty number of a SALES invoice becomes the next one of
 * its issue month (FV/2026/11/001); a PURCHASE invoice needs the supplier's number.
 */
export interface InvoiceInput {
  direction: InvoiceDirection;
  number: string | null;
  contractorId: number;
  issueDate: string;
  /** Empty: issue date + the contractor's payment days (or 14). */
  dueDate: string | null;
  currency: string;
  lines: InvoiceLineInput[];
  net: number | null;
  vat: number | null;
  gross: number | null;
  orderIds: number[];
  tripId: number | null;
  remindersOn: boolean;
  cancelled: boolean;
  notes: string | null;
}

export interface InvoiceFilter {
  direction?: InvoiceDirection;
  status?: InvoiceStatus;
  contractorId?: number;
  q?: string;
  from?: string;
  to?: string;
  overdue?: boolean;
  page?: number;
  size?: number;
}

export interface InvoiceTotal {
  currency: string;
  gross: number;
  open: number;
}

export interface InvoicePaymentInput {
  paidAt: string;
  amount: number;
  method: PaymentMethod;
  reference: string | null;
  note: string | null;
}

export interface DebtRow {
  contractor: Ref;
  currency: string;
  open: number;
  overdue: number;
  invoices: number;
  oldestDueDate: string | null;
  maxDaysOverdue: number;
  buckets: { notDue: number; d1to30: number; d31to60: number; d61to90: number; over90: number };
}

export interface ReminderStep {
  /** Days from the due date: -3 = three days before, 7 = a week after. -60..365, unique. */
  offsetDays: number;
  /** A firmer text (and the cc addresses) for this step. */
  escalate: boolean;
}

export interface ReminderSchedule {
  /** Hour of the day (firma zone) the daily job sends at, 0..23. */
  sendHour: number;
  ccEmails: string[];
  stopWhenPaid: boolean;
  steps: ReminderStep[];
}

export const financeApi = {
  list: (filter: InvoiceFilter) =>
    api<{ items: Invoice[]; total: number; totals: InvoiceTotal[] }>('/invoices', { query: { ...filter } }),
  get: (id: number | string) => api<InvoiceDetail>(`/invoices/${id}`),
  create: (body: InvoiceInput) => api<InvoiceDetail>('/invoices', { method: 'POST', body }),
  update: (id: number | string, body: InvoiceInput) => api<InvoiceDetail>(`/invoices/${id}`, { method: 'PUT', body }),
  remove: (id: number | string) => api<void>(`/invoices/${id}`, { method: 'DELETE' }),
  addPayment: (id: number | string, body: InvoicePaymentInput) =>
    api<InvoicePayment>(`/invoices/${id}/payments`, { method: 'POST', body }),
  removePayment: (id: number | string, paymentId: number) =>
    api<void>(`/invoices/${id}/payments/${paymentId}`, { method: 'DELETE' }),
  reminders: (id: number | string) => api<Reminder[]>(`/invoices/${id}/reminders`),
  /** A reminder now, by hand; 422 finance.reminder.no_email when the contractor has no invoice e-mail. */
  sendReminder: (id: number | string) => api<Reminder>(`/invoices/${id}/reminders`, { method: 'POST' }),
  debts: (asOf?: string) => api<DebtRow[]>('/debts', { query: { asOf } }),
  schedule: () => api<ReminderSchedule>('/reminders/schedule'),
  saveSchedule: (body: ReminderSchedule) => api<ReminderSchedule>('/reminders/schedule', { method: 'PUT', body }),
};

export const INVOICES_KEY = ['invoices'] as const;
export const DEBTS_KEY = ['debts'] as const;
export const REMINDER_SCHEDULE_KEY = ['reminders', 'schedule'] as const;

/** Net, VAT and gross of a line, as the server rounds them (VAT per line, 2 decimals). */
export function lineAmounts(l: Pick<InvoiceLineInput, 'qty' | 'unitPrice' | 'vatCode' | 'vatRate'>): { net: number; vat: number; gross: number } {
  const r2 = (v: number) => Math.round((v + Number.EPSILON) * 100) / 100;
  const net = r2(l.qty * l.unitPrice);
  const vat = l.vatCode === 'RATE' && l.vatRate != null ? r2((net * l.vatRate) / 100) : 0;
  return { net, vat, gross: r2(net + vat) };
}
