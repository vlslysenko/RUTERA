import type { OrderPlace } from '@/api/trips';
import { formatDate } from '@/utils/deadline';

/** "1 250,00 zł" in the UI language; a dash without a price. */
export function formatMoney(value: number | null, currency: string | null, lang: string): string {
  if (value == null) return '—';
  if (!currency) return new Intl.NumberFormat(lang, { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(value);
  try {
    return new Intl.NumberFormat(lang, { style: 'currency', currency }).format(value);
  } catch {
    return `${value.toFixed(2)} ${currency}`;
  }
}

/** "Wrocław, PL · 03.11.2026" */
export function formatPlace(p: OrderPlace | null, lang: string): string {
  if (!p) return '—';
  return [`${p.place}, ${p.country}`, p.date ? formatDate(p.date, lang) : null].filter(Boolean).join(' · ');
}

/** ISO instant -> value of <input type="datetime-local"> in local time. */
export function toLocalInput(iso: string | null): string {
  if (!iso) return '';
  const d = new Date(iso);
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}`;
}

/** <input type="datetime-local"> value -> ISO instant (or null when empty). */
export function fromLocalInput(v: string): string | null {
  return v ? new Date(v).toISOString() : null;
}

/** Short date and time; in `timeZone` (the firma's) when given, else in the browser's zone. */
export function formatInstantShort(iso: string | null, lang: string, timeZone?: string): string {
  return iso ? new Intl.DateTimeFormat(lang, { dateStyle: 'short', timeStyle: 'short', timeZone }).format(new Date(iso)) : '—';
}

/** Currencies offered in money fields; anything else can be typed (3 letters). */
export const CURRENCIES = ['PLN', 'EUR', 'CZK', 'HUF', 'RON', 'BGN', 'SEK', 'DKK', 'NOK', 'CHF', 'GBP', 'UAH', 'USD'];
