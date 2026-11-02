import type { TFunction } from 'i18next';
import { splitMinutes, type ViolationRule } from '@/api/hos';

/** "9 h 05 min" in the UI language. */
export function formatMinutes(t: TFunction, min: number): string {
  const { h, m } = splitMinutes(min);
  return t('hos.hm', { h, m: String(m).padStart(2, '0') });
}

/** Rest rules are broken by too little (short by); driving and break rules by too much (over by). */
export const REST_RULES: readonly ViolationRule[] = ['DAILY_REST_11H', 'DAILY_REST_9H', 'WEEKLY_REST_45H', 'WEEKLY_REST_24H'];

/** Monday (YYYY-MM-DD) of the week that contains `date`, in local time. */
export function mondayOf(date: Date): string {
  const d = new Date(date.getFullYear(), date.getMonth(), date.getDate());
  d.setDate(d.getDate() - ((d.getDay() + 6) % 7));
  return toYmd(d);
}

export function toYmd(d: Date): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

export function shiftDays(ymd: string, n: number): string {
  const [y, m, d] = ymd.split('-').map(Number);
  return toYmd(new Date(y, m - 1, d + n));
}

/** Local midnight of a YYYY-MM-DD day, as epoch milliseconds. */
export function dayStart(ymd: string): number {
  const [y, m, d] = ymd.split('-').map(Number);
  return new Date(y, m - 1, d).getTime();
}
