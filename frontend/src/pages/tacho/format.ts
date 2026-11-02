import { formatDate } from '@/utils/deadline';

const DASH = '—';

/** "01.10.2026 – 28.10.2026" for a YYYY-MM-DD period; a dash when nothing is known. */
export function formatPeriod(from: string | null, to: string | null, lang: string): string {
  if (!from && !to) return DASH;
  return `${from ? formatDate(from.slice(0, 10), lang) : '…'} – ${to ? formatDate(to.slice(0, 10), lang) : '…'}`;
}

/** Date and time of an instant; in `timeZone` (the firma's) when given, else in the browser's zone. */
export function formatInstant(iso: string | null, lang: string, timeZone?: string): string {
  return iso ? new Intl.DateTimeFormat(lang, { dateStyle: 'short', timeStyle: 'short', timeZone }).format(new Date(iso)) : DASH;
}

/** File size in kB or MB in the UI language. */
export function formatSize(bytes: number, lang: string): string {
  const nf = (v: number, d: number) => new Intl.NumberFormat(lang, { maximumFractionDigits: d }).format(v);
  return bytes >= 1024 * 1024 ? `${nf(bytes / 1024 / 1024, 1)} MB` : `${nf(Math.max(1, bytes / 1024), 0)} kB`;
}
