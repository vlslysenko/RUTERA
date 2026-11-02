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

/** Offset of a time zone from UTC at an instant, in milliseconds (Europe/Warsaw in summer: +2 h). */
function zoneOffset(ms: number, timeZone: string): number {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone,
    hourCycle: 'h23',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  }).formatToParts(new Date(ms));
  const n = (type: string) => Number(parts.find((p) => p.type === type)?.value);
  return Date.UTC(n('year'), n('month') - 1, n('day'), n('hour'), n('minute'), n('second')) - Math.floor(ms / 1000) * 1000;
}

/**
 * Midnight of a YYYY-MM-DD day in the given time zone (the firma's: days are cut there), as epoch milliseconds.
 * Days around a DST change are 23 or 25 hours long: take the length as the next day's start minus this one.
 */
export function zonedDayStart(ymd: string, timeZone: string): number {
  const [y, m, d] = ymd.split('-').map(Number);
  const guess = Date.UTC(y, m - 1, d);
  const first = guess - zoneOffset(guess, timeZone);
  return guess - zoneOffset(first, timeZone);
}

/** YYYY-MM-DD of an instant in a time zone. */
export function ymdIn(iso: string, timeZone: string): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone, year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date(iso));
}

/** Monday (YYYY-MM-DD) of the week that contains a YYYY-MM-DD day. */
export function mondayOfDay(ymd: string): string {
  const [y, m, d] = ymd.split('-').map(Number);
  return shiftDays(ymd, -((new Date(Date.UTC(y, m - 1, d)).getUTCDay() + 6) % 7));
}
