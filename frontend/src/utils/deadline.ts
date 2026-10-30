import type { StatusTone } from '@/components/StatusChip';

/** Reminder window when a document does not say otherwise (vehicle_document.remind_days default). */
export const DEFAULT_REMIND_DAYS = 30;

/** Whole days from today (local calendar) to a YYYY-MM-DD date; negative when the date has passed. */
export function daysUntil(isoDate: string, today: Date = new Date()): number {
  const [y, m, d] = isoDate.split('-').map(Number);
  const target = Date.UTC(y, m - 1, d);
  const now = Date.UTC(today.getFullYear(), today.getMonth(), today.getDate());
  return Math.round((target - now) / 86_400_000);
}

/** Expired = critical, inside the reminder window = warning, otherwise fine. */
export function deadlineTone(days: number, remindDays = DEFAULT_REMIND_DAYS): StatusTone {
  if (days < 0) return 'crit';
  if (days <= remindDays) return 'warn';
  return 'good';
}

/** Formats a YYYY-MM-DD date in the UI language without shifting it through the time zone. */
export function formatDate(isoDate: string, language: string): string {
  const [y, m, d] = isoDate.split('-').map(Number);
  return new Intl.DateTimeFormat(language, { day: '2-digit', month: '2-digit', year: 'numeric', timeZone: 'UTC' }).format(
    new Date(Date.UTC(y, m - 1, d)),
  );
}
