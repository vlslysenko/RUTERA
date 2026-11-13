import { api } from './client';

/*
 * Driver activities and rules 561/2006 (DB_SCHEMA.md group 4: tacho_activity, driver_hos_day, driver_violation),
 * backend b4b6475. Intervals are the recorded data only: time without a card comes as UNKNOWN, days without data have
 * no intervals; for 561/2006 both count as rest. Days are cut in the firma time zone, weeks Monday to Sunday; `days`
 * holds every day of the range. Violations are recomputed after each upload, assignment or delete of a file of the
 * driver; a review survives when the same violation (driver, rule, startAt) is found again.
 *   GET  /app/tacho/activity?driverId&from&to  -> DriverActivity (tacho.hos/view, up to 31 days)
 *   GET  /app/tacho/violations?driverId&from&to&severity&unacknowledged&page&size -> {items, total}, newest first
 *   GET  /app/tacho/violations/{id}            -> Violation
 *   POST /app/tacho/violations/{id}/ack {note?} -> Violation (tacho.violation/ack)
 *   DELETE /app/tacho/violations/{id}/ack      -> Violation
 */

export const ACTIVITIES = ['DRIVING', 'WORK', 'AVAILABILITY', 'REST', 'UNKNOWN'] as const;
export type Activity = (typeof ACTIVITIES)[number];

export const SEVERITIES = ['MINOR', 'SERIOUS', 'VERY_SERIOUS', 'MOST_SERIOUS'] as const;
export type Severity = (typeof SEVERITIES)[number];

export const VIOLATION_RULES = [
  'DAILY_DRIVING_9H',
  'DAILY_DRIVING_10H',
  'WEEKLY_DRIVING_56H',
  'TWO_WEEK_DRIVING_90H',
  'BREAK_45_AFTER_4H30',
  'DAILY_REST_11H',
  'DAILY_REST_9H',
  'WEEKLY_REST_45H',
  'WEEKLY_REST_24H',
] as const;
export type ViolationRule = (typeof VIOLATION_RULES)[number];

/** Limits of 561/2006 used by the summary (minutes). */
export const HOS = {
  DAILY_DRIVING: 9 * 60,
  DAILY_DRIVING_EXTENDED: 10 * 60,
  EXTENSIONS_PER_WEEK: 2,
  REDUCED_RESTS_BETWEEN_WEEKLY: 3,
  WEEKLY_DRIVING: 56 * 60,
  TWO_WEEK_DRIVING: 90 * 60,
} as const;

/** Vehicle of a tachograph record: id is null when the plate / VIN of the file is not in the fleet. */
export interface TachoVehicleRef {
  id: number | null;
  plate: string;
}

export interface ActivityInterval {
  /** ISO UTC */
  startAt: string;
  endAt: string;
  activity: Activity;
  /** 1 = driver, 2 = co-driver (crew). */
  slot: 1 | 2;
  vehicle: TachoVehicleRef | null;
}

export interface HosDay {
  /** YYYY-MM-DD in the firma time zone */
  day: string;
  drivingMin: number;
  workMin: number;
  availabilityMin: number;
  restMin: number;
  /** Length of the daily or weekly rest (9 h or more) that started this day; null without one. */
  dailyRestMin: number | null;
  extDrivingInWeek: number;
  reducedRestInWeek: number;
  weeklyDrivingMin: number;
  biweeklyDrivingMin: number;
  remainingDailyMin: number;
  remainingWeeklyMin: number;
  /** ISO UTC */
  nextWeeklyRestBy: string | null;
  violations: number;
}

export interface DriverActivity {
  driver: { id: number; name: string };
  /** Time zone of the firma, e.g. Europe/Warsaw: days are cut in it. */
  timezone: string;
  days: HosDay[];
  intervals: ActivityInterval[];
}

export interface Violation {
  id: number;
  driver: { id: number; name: string };
  rule: ViolationRule;
  severity: Severity;
  startAt: string;
  endAt: string;
  /** Minutes: what was measured and what the rule allows. */
  actualValue: number;
  limitValue: number;
  vehicle: TachoVehicleRef | null;
  /** Short technical English text, not translated. */
  details: string | null;
  ack: { by: { id: number; name: string }; at: string; note: string | null } | null;
  /** The driver's explanation of it, when one was asked for (proposed with the explanations contract). */
  explanationId?: number | null;
  explanationStatus?: 'PENDING' | 'SIGNED' | 'CANCELLED' | null;
}

export interface ViolationFilter {
  driverId?: number;
  from?: string;
  to?: string;
  severity?: Severity;
  unacknowledged?: boolean;
  page?: number;
  size?: number;
}

export const hosApi = {
  activity: (driverId: number, from: string, to: string) =>
    api<DriverActivity>('/tacho/activity', { query: { driverId, from, to } }),
  violations: (filter: ViolationFilter) =>
    api<{ items: Violation[]; total: number }>('/tacho/violations', { query: { ...filter } }),
  violation: (id: number | string) => api<Violation>(`/tacho/violations/${id}`),
  ack: (id: number | string, note: string | null) =>
    api<Violation>(`/tacho/violations/${id}/ack`, { method: 'POST', body: { note } }),
  unack: (id: number | string) => api<Violation>(`/tacho/violations/${id}/ack`, { method: 'DELETE' }),
};

export const HOS_KEY = ['tacho', 'hos'] as const;
export const VIOLATIONS_KEY = ['tacho', 'violations'] as const;

/** "9 h 05 min" style formatting of minutes; hours and minutes words come from i18n. */
export function splitMinutes(min: number): { h: number; m: number } {
  const total = Math.max(0, Math.round(min));
  return { h: Math.floor(total / 60), m: total % 60 };
}
