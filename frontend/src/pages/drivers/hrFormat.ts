import { NEGATIVE_PAYMENT_KINDS, type PaymentKind } from '@/api/hr';
import { formatDate } from '@/utils/deadline';

/** YYYY-MM of a date in local time. */
export function monthOf(d: Date = new Date()): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
}

export function shiftMonth(ym: string, n: number): string {
  const [y, m] = ym.split('-').map(Number);
  return monthOf(new Date(y, m - 1 + n, 1));
}

export function daysInMonth(ym: string): number {
  const [y, m] = ym.split('-').map(Number);
  return new Date(Date.UTC(y, m, 0)).getUTCDate();
}

/** First and last day (YYYY-MM-DD) of a month. */
export function monthRange(ym: string): [string, string] {
  return [`${ym}-01`, `${ym}-${String(daysInMonth(ym)).padStart(2, '0')}`];
}

/** "listopad 2026" in the UI language. */
export function formatMonth(ym: string, lang: string): string {
  const [y, m] = ym.split('-').map(Number);
  return new Intl.DateTimeFormat(lang, { month: 'long', year: 'numeric', timeZone: 'UTC' }).format(new Date(Date.UTC(y, m - 1, 1)));
}

/** Weekday of a YYYY-MM-DD day: 0 = Monday … 6 = Sunday. */
export function weekdayIndex(ymd: string): number {
  const [y, m, d] = ymd.split('-').map(Number);
  return (new Date(Date.UTC(y, m - 1, d)).getUTCDay() + 6) % 7;
}

/** Short weekday names Monday first, in the UI language. */
export function weekdayNames(lang: string): string[] {
  const f = new Intl.DateTimeFormat(lang, { weekday: 'short', timeZone: 'UTC' });
  // 2024-01-01 was a Monday.
  return Array.from({ length: 7 }, (_, i) => f.format(new Date(Date.UTC(2024, 0, 1 + i))));
}

/** "03.11.2026 – 07.11.2026", one date when both are equal. */
export function formatRange(from: string, to: string, lang: string): string {
  return from === to ? formatDate(from, lang) : `${formatDate(from, lang)} – ${formatDate(to, lang)}`;
}

/** Amount with its currency; advances and deductions shown with a minus. */
export function formatPayment(amount: number, currency: string, kind: PaymentKind, lang: string): string {
  return formatAmount(NEGATIVE_PAYMENT_KINDS.includes(kind) ? -amount : amount, currency, lang);
}

export function formatAmount(amount: number | null, currency: string, lang: string, fractionDigits = 2): string {
  if (amount == null) return '—';
  try {
    return new Intl.NumberFormat(lang, { style: 'currency', currency, minimumFractionDigits: fractionDigits, maximumFractionDigits: fractionDigits }).format(amount);
  } catch {
    return `${amount.toFixed(fractionDigits)} ${currency}`;
  }
}

/** "7:45" for minutes, compact enough for a calendar cell. */
export function hmm(min: number): string {
  return `${Math.floor(min / 60)}:${String(min % 60).padStart(2, '0')}`;
}
