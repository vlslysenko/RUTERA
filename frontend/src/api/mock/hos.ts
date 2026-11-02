import type { ActivityInterval, HosDay, Severity, Violation, ViolationRule } from '@/api/hos';
import { HOS } from '@/api/hos';

/* Activities, day totals and violations for the in-browser mock: a seeded generator gives every driver a plausible
 * working week (shifts on weekdays, rest at weekends), and the violations are derived from the same days, so the
 * activity view and the violation list always agree. Days are local calendar days of the browser. */

export interface MockShiftDriver {
  id: number;
  name: string;
  vehicle: { id: number; plate: string } | null;
}

function seeded(seed: number): () => number {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const ymd = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;

export function parseDay(day: string): Date {
  const [y, m, d] = day.split('-').map(Number);
  return new Date(y, m - 1, d);
}

export function addDays(day: string, n: number): string {
  const d = parseDay(day);
  d.setDate(d.getDate() + n);
  return ymd(d);
}

/** Monday of the week of `day` (561/2006 weeks run Monday 00:00 to Sunday 24:00). */
export function weekStart(day: string): string {
  const d = parseDay(day);
  return addDays(day, -((d.getDay() + 6) % 7));
}

interface Segment {
  activity: ActivityInterval['activity'];
  min: number;
}

interface GeneratedDay {
  day: string;
  /** Minutes from local midnight. */
  start: number;
  segments: Segment[];
}

/** The shift of one driver on one day, or null for a day off. Deterministic for (driver, day). */
function shift(driverId: number, day: string): GeneratedDay | null {
  const rnd = seeded(driverId * 100_003 + parseDay(day).getTime() / 86_400_000);
  const dow = parseDay(day).getDay();
  if (dow === 0 || (dow === 6 && rnd() < 0.7) || rnd() < 0.06) return null;
  if (parseDay(day) > new Date()) return null;
  const r = (lo: number, hi: number) => Math.round(lo + rnd() * (hi - lo));
  const segments: Segment[] = [
    { activity: 'WORK', min: r(15, 40) },
    { activity: 'DRIVING', min: r(190, rnd() < 0.15 ? 310 : 265) },
    { activity: 'REST', min: rnd() < 0.12 ? r(20, 35) : r(45, 60) },
    { activity: 'DRIVING', min: r(150, 260) },
  ];
  if (rnd() < 0.5) segments.push({ activity: 'AVAILABILITY', min: r(20, 60) });
  segments.push({ activity: 'WORK', min: r(20, 60) });
  if (rnd() < 0.35) segments.push({ activity: 'DRIVING', min: r(30, rnd() < 0.3 ? 150 : 70) });
  segments.push({ activity: 'WORK', min: r(10, 25) });
  return { day, start: r(4 * 60, 7 * 60 + 30), segments };
}

const sum = (g: GeneratedDay | null, a: Segment['activity']) =>
  g ? g.segments.filter((s) => s.activity === a).reduce((acc, s) => acc + s.min, 0) : 0;
const span = (g: GeneratedDay) => g.segments.reduce((acc, s) => acc + s.min, 0);

/** Longest driving without a 45-minute break (breaks shorter than that do not reset it). */
function longestDriving(g: GeneratedDay): number {
  let cur = 0;
  let max = 0;
  for (const s of g.segments) {
    if (s.activity === 'DRIVING') cur += s.min;
    else if (s.activity === 'REST' && s.min >= 45) cur = 0;
    max = Math.max(max, cur);
  }
  return max;
}

function iso(day: string, minutesFromMidnight: number): string {
  const d = parseDay(day);
  d.setMinutes(minutesFromMidnight);
  return d.toISOString();
}

export function intervalsFor(driver: MockShiftDriver, from: string, to: string): ActivityInterval[] {
  const out: ActivityInterval[] = [];
  for (let day = from; day <= to; day = addDays(day, 1)) {
    const g = shift(driver.id, day);
    let t = 0;
    const push = (activity: ActivityInterval['activity'], a: number, b: number) => {
      if (b > a) out.push({ startAt: iso(day, a), endAt: iso(day, b), activity, slot: 1, vehicle: activity === 'REST' ? null : driver.vehicle });
    };
    if (g) {
      push('REST', 0, g.start);
      t = g.start;
      for (const s of g.segments) {
        push(s.activity, t, Math.min(t + s.min, 24 * 60));
        t += s.min;
      }
    }
    push('REST', Math.min(t, 24 * 60), 24 * 60);
  }
  return out;
}

export function daysFor(driver: MockShiftDriver, from: string, to: string, violationsPerDay: Map<string, number>): HosDay[] {
  const out: HosDay[] = [];
  for (let day = from; day <= to; day = addDays(day, 1)) {
    const ws = weekStart(day);
    let weekly = 0;
    let ext = 0;
    let reduced = 0;
    for (let d = ws; d <= day; d = addDays(d, 1)) {
      const g = shift(driver.id, d);
      const drv = sum(g, 'DRIVING');
      weekly += drv;
      if (drv > HOS.DAILY_DRIVING) ext += 1;
      if (g && 24 * 60 - span(g) < 11 * 60) reduced += 1;
    }
    let previousWeek = 0;
    for (let d = addDays(ws, -7); d < ws; d = addDays(d, 1)) previousWeek += sum(shift(driver.id, d), 'DRIVING');
    const g = shift(driver.id, day);
    const driving = sum(g, 'DRIVING');
    const dailyLimit = ext <= HOS.EXTENSIONS_PER_WEEK ? HOS.DAILY_DRIVING_EXTENDED : HOS.DAILY_DRIVING;
    out.push({
      day,
      drivingMin: driving,
      workMin: sum(g, 'WORK'),
      availabilityMin: sum(g, 'AVAILABILITY'),
      restMin: 24 * 60 - (g ? span(g) : 0) + sum(g, 'REST'),
      dailyRestMin: g ? 24 * 60 - span(g) : null,
      extDrivingInWeek: ext,
      reducedRestInWeek: reduced,
      weeklyDrivingMin: weekly,
      biweeklyDrivingMin: weekly + previousWeek,
      remainingDailyMin: Math.max(0, dailyLimit - driving),
      remainingWeeklyMin: Math.max(0, Math.min(HOS.WEEKLY_DRIVING - weekly, HOS.TWO_WEEK_DRIVING - weekly - previousWeek)),
      nextWeeklyRestBy: iso(addDays(ws, 7), 0),
      violations: violationsPerDay.get(day) ?? 0,
    });
  }
  return out;
}

function severityFor(overMin: number, steps: [number, number, number]): Severity {
  if (overMin >= steps[2]) return 'MOST_SERIOUS';
  if (overMin >= steps[1]) return 'VERY_SERIOUS';
  if (overMin >= steps[0]) return 'SERIOUS';
  return 'MINOR';
}

/** Violations of the last `days` days for the given drivers, newest first. */
export function buildViolations(drivers: MockShiftDriver[], today: string, days: number): Omit<Violation, 'id'>[] {
  const out: Omit<Violation, 'id'>[] = [];
  const add = (d: MockShiftDriver, rule: ViolationRule, day: string, a: number, b: number, actual: number, limit: number, severity: Severity) =>
    out.push({
      driver: { id: d.id, name: d.name },
      rule,
      severity,
      startAt: iso(day, a),
      endAt: iso(day, b),
      actualValue: actual,
      limitValue: limit,
      vehicle: d.vehicle,
      details: null,
      ack: null,
    });
  for (const d of drivers) {
    for (let day = addDays(today, -days); day <= today; day = addDays(day, 1)) {
      const g = shift(d.id, day);
      if (!g) continue;
      const driving = sum(g, 'DRIVING');
      const end = g.start + span(g);
      if (driving > HOS.DAILY_DRIVING_EXTENDED) {
        add(d, 'DAILY_DRIVING_10H', day, g.start, end, driving, HOS.DAILY_DRIVING_EXTENDED, severityFor(driving - 600, [60, 120, 270]));
      }
      const longest = longestDriving(g);
      if (longest > 270) {
        add(d, 'BREAK_45_AFTER_4H30', day, g.start, end, longest, 270, severityFor(longest - 270, [30, 90, 1000]));
      }
      const rest = 24 * 60 - span(g);
      if (rest < 9 * 60) add(d, 'DAILY_REST_9H', day, end, 24 * 60 + g.start, rest, 9 * 60, severityFor(540 - rest, [60, 150, 1000]));
    }
  }
  return out.sort((a, b) => b.startAt.localeCompare(a.startAt));
}

export { ymd };
