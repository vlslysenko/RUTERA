import type { CalcDriverState, CalcPlan, CalcRequest, CostLine, TimelineItem, TimelineKind, TollTariff, TollTariffInput } from '@/api/calc';

/*
 * Trip calculator for the in-browser mock API: a simplified 561/2006 planner (break 45 min after 4.5 h, daily driving
 * 9 h or 10 h twice a week, daily rest 11 h or reduced 9 h, weekly driving 56 h, crew of two alternating every
 * 4.5 h with a 9 h rest within 30 h) and costs from the vehicle, pay rates, toll tariffs and fuel transactions.
 * Close enough to show the screens; the real engine is the backend's.
 */

export interface MockReply {
  status: number;
  body?: unknown;
}

export interface CalcLookup {
  vehicle(id: number): { id: number; avgL100km: number | null; euroClass: string | null; axles: number | null; grossWeightKg: number | null } | undefined;
  driverExists(id: number): boolean;
  payRate(driverId: number, day: string): { monthlyBase: number | null; perDiemHome: number | null; perDiemAbroad: number | null; perKm: number | null; currency: string } | undefined;
  dieselPrice(): { price: number; currency: string } | undefined;
  canSeePay: boolean;
  firmaCountry: string;
}

/** Reference fuel prices (GET /app/calc/fuel-prices): diesel per litre, net, in the country's currency. */
export function listFuelPrices() {
  const row = (id: number, country: string, net: number, vatRate: number, currency: string) => ({
    id, country, product: 'DIESEL', priceDate: '2026-11-02', net, gross: Math.round(net * (1 + vatRate / 100) * 1000) / 1000, vatRate,
    excisePer1000l: null, currency, kind: 'NATIONAL', source: 'EU Weekly Oil Bulletin', fetchedAt: '2026-11-03T06:00:00Z', updatedAt: '2026-11-03T06:00:00Z',
  });
  return [row(1, 'PL', 5.21, 23, 'PLN'), row(2, 'DE', 1.401, 19, 'EUR'), row(3, 'CZ', 31.9, 21, 'CZK'), row(4, 'FR', 1.398, 20, 'EUR')];
}

const invalid = (details: Record<string, string>): MockReply => ({ status: 422, body: { code: 'error.validation', details } });
const r2 = (v: number) => Math.round((v + Number.EPSILON) * 100) / 100;
const MIN = 60_000;

const BREAK_AFTER = 270;
const BREAK = 45;
const DAILY = 540;
const DAILY_EXT = 600;
const DAILY_REST = 660;
const REDUCED_DAILY_REST = 540;
const WEEKLY_DRIVING = 3360;
const WEEKLY_REST = 2700;
const REDUCED_WEEKLY_REST = 1440;
const CREW_SHIFT = 21 * 60;

let nextId = 900;
const tariffs: TollTariff[] = [
  { id: ++nextId, country: 'DE', euroClass: 'EURO_6', axlesMin: 4, weightMinKg: 18000, ratePerKm: 0.348, currency: 'EUR', validFrom: '2024-01-01', notes: 'Maut 5+ Achsen, CO2-Klasse 1 (Beispiel)' },
  { id: ++nextId, country: 'PL', euroClass: null, axlesMin: null, weightMinKg: 12000, ratePerKm: 0.62, currency: 'PLN', validFrom: null, notes: 'e-TOLL (przykład)' },
];

interface Driver {
  sinceBreak: number;
  inShift: number;
  shiftStart: number | null;
  week: number;
  fortnight: number;
  extLeft: number;
  reducedLeft: number;
}

function initialState(req: CalcRequest, startMs: number, warnings: string[], missing: string[]): { states: CalcDriverState[]; drivers: Driver[] } {
  const crew = req.crewSize ?? (req.driverIds.length === 2 ? 2 : 1);
  const source = req.driverState ?? (req.driverIds.length ? 'TACHO' : 'FRESH');
  const drivers: Driver[] = [];
  const states: CalcDriverState[] = [];
  for (let i = 0; i < crew; i++) {
    let d: Driver = { sinceBreak: 0, inShift: 0, shiftStart: null, week: 0, fortnight: 0, extLeft: 2, reducedLeft: 3 };
    let dataUntil: string | null = null;
    if (source === 'MANUAL' && req.manualState) {
      const m = req.manualState;
      d = {
        sinceBreak: m.drivingSinceBreakMin, inShift: m.drivingInShiftMin, shiftStart: m.shiftStart ? Date.parse(m.shiftStart) : null,
        week: m.weekDrivingMin, fortnight: m.fortnightDrivingMin, extLeft: m.extensionsLeft, reducedLeft: m.reducedRestsLeft,
      };
    } else if (source === 'TACHO') {
      if (req.driverIds[i] == null) missing.push('calc.missing.tacho_data');
      else {
        // The mock has no 28-day history: a plausible week already driven, data until yesterday evening.
        d = { sinceBreak: 0, inShift: 0, shiftStart: null, week: 1500 + i * 120, fortnight: 4100, extLeft: 1, reducedLeft: 2 };
        dataUntil = new Date(startMs - 14 * 60 * MIN).toISOString();
        if (startMs - Date.parse(dataUntil) > 3 * 86_400_000) warnings.push('calc.warning.tacho_old');
      }
    }
    drivers.push(d);
    states.push({
      driverId: req.driverIds[i] ?? null, source, dataUntil, drivingSinceBreakMin: d.sinceBreak, drivingInShiftMin: d.inShift,
      shiftStart: d.shiftStart ? new Date(d.shiftStart).toISOString() : null, weekDrivingMin: d.week, fortnightDrivingMin: d.fortnight,
      extensionsLeft: d.extLeft, reducedRestsLeft: d.reducedLeft, weeklyRestDueAt: new Date(startMs + (WEEKLY_DRIVING - d.week) * MIN + 2 * 86_400_000).toISOString(),
    });
  }
  return { states, drivers };
}

export function plan(req: CalcRequest, lk: CalcLookup): MockReply {
  const errors: Record<string, string> = {};
  if (!req.start || Number.isNaN(Date.parse(req.start))) errors.start = 'validation.required';
  if (!req.legs?.length) errors.legs = 'validation.required';
  (req.legs ?? []).forEach((l, i) => {
    if (l.distanceKm == null || l.distanceKm < 0 || l.distanceKm > 20_000) errors[`legs[${i}].distanceKm`] = 'validation.invalid_value';
    if (l.durationMin != null && (l.durationMin < 0 || l.durationMin > 30_000)) errors[`legs[${i}].durationMin`] = 'validation.invalid_value';
    if (l.serviceMin != null && (l.serviceMin < 0 || l.serviceMin > 1440)) errors[`legs[${i}].serviceMin`] = 'validation.invalid_value';
    if (l.countries?.length && Math.abs(l.countries.reduce((s, c) => s + c.km, 0) - l.distanceKm) > 1) errors[`legs[${i}].countries`] = 'validation.invalid_value';
  });
  if ((req.driverIds ?? []).length > 2) errors.driverIds = 'validation.invalid_value';
  for (const id of req.driverIds ?? []) if (!lk.driverExists(id)) errors.driverIds = 'validation.invalid_value';
  if (req.vehicleId != null && !lk.vehicle(req.vehicleId)) errors.vehicleId = 'validation.invalid_value';
  if (req.driverState === 'MANUAL' && !req.manualState) errors.manualState = 'validation.required';
  if (req.options?.avgSpeedKmh != null && (req.options.avgSpeedKmh < 20 || req.options.avgSpeedKmh > 120)) errors['options.avgSpeedKmh'] = 'validation.invalid_value';
  if (Object.keys(errors).length) return invalid(errors);

  const startMs = Date.parse(req.start);
  const warnings: string[] = [];
  const missing: string[] = [];
  const { states, drivers } = initialState(req, startMs, warnings, missing);
  const crew = drivers.length;
  const opts = req.options;
  const speed = opts.avgSpeedKmh ?? 70;
  const timeline: TimelineItem[] = [];
  let now = startMs;
  let km = 0;
  let current = 0;
  let crewShiftStart: number | null = null;

  const push = (kind: TimelineKind, minutes: number, legIndex: number, kmTo = km, driver: 1 | 2 | null = null) => {
    if (minutes <= 0) return;
    const prev = timeline.at(-1);
    if (prev && prev.kind === kind && prev.driver === driver && prev.legIndex === legIndex && kind === 'DRIVE') {
      prev.end = new Date(now + minutes * MIN).toISOString();
      prev.minutes += minutes;
      prev.kmTo = kmTo;
    } else {
      timeline.push({ kind, start: new Date(now).toISOString(), end: new Date(now + minutes * MIN).toISOString(), minutes, kmFrom: km, kmTo, legIndex, driver });
    }
    now += minutes * MIN;
  };

  const dailyRest = (legIndex: number) => {
    const reduced = opts.reducedRests && drivers.every((d) => d.reducedLeft > 0);
    const minutes = crew === 2 ? REDUCED_DAILY_REST : reduced ? REDUCED_DAILY_REST : DAILY_REST;
    push(crew === 2 || reduced ? 'REDUCED_DAILY_REST' : 'DAILY_REST', minutes, legIndex);
    for (const d of drivers) {
      if (crew === 1 && reduced) d.reducedLeft--;
      if (d.inShift > DAILY) d.extLeft--;
      d.inShift = 0;
      d.sinceBreak = 0;
      d.shiftStart = null;
    }
    crewShiftStart = null;
  };

  const weeklyRest = (legIndex: number) => {
    const reduced = opts.reducedWeeklyRest;
    push(reduced ? 'REDUCED_WEEKLY_REST' : 'WEEKLY_REST', reduced ? REDUCED_WEEKLY_REST : WEEKLY_REST, legIndex);
    for (const d of drivers) Object.assign(d, { sinceBreak: 0, inShift: 0, shiftStart: null, week: 0, extLeft: 2, reducedLeft: 3 });
    crewShiftStart = null;
    if (!warnings.includes('calc.warning.weekly_rest_needed')) warnings.push('calc.warning.weekly_rest_needed');
  };

  req.legs.forEach((leg, li) => {
    const legMinutes = leg.durationMin ?? Math.round((leg.distanceKm / speed) * 60);
    const kmPerMin = legMinutes > 0 ? leg.distanceKm / legMinutes : 0;
    let left = legMinutes;
    let guard = 0;
    while (left > 0 && guard++ < 1000) {
      const d = drivers[current];
      const limit = opts.extendedDriving && d.extLeft > 0 ? DAILY_EXT : DAILY;
      if (d.week >= WEEKLY_DRIVING) {
        weeklyRest(li);
        continue;
      }
      if (crew === 2) {
        const other = drivers[1 - current];
        const shiftUsed = crewShiftStart == null ? 0 : (now - crewShiftStart) / MIN;
        const otherLimit = opts.extendedDriving && other.extLeft > 0 ? DAILY_EXT : DAILY;
        if (shiftUsed >= CREW_SHIFT || (d.inShift >= limit && other.inShift >= otherLimit)) {
          dailyRest(li);
          continue;
        }
        if (d.sinceBreak >= BREAK_AFTER || d.inShift >= limit) {
          // The co-driver takes over; the one who drove has his break on the passenger seat.
          d.sinceBreak = 0;
          current = 1 - current;
          continue;
        }
      } else {
        if (d.inShift >= limit) {
          dailyRest(li);
          continue;
        }
        if (d.sinceBreak >= BREAK_AFTER) {
          push('BREAK', BREAK, li);
          d.sinceBreak = 0;
          continue;
        }
      }
      const chunk = Math.min(left, BREAK_AFTER - d.sinceBreak, limit - d.inShift, WEEKLY_DRIVING - d.week);
      if (crewShiftStart == null) crewShiftStart = now;
      if (d.shiftStart == null) d.shiftStart = now;
      const kmTo = Math.min(km + chunk * kmPerMin, km + leg.distanceKm);
      push('DRIVE', chunk, li, r2(kmTo), (current + 1) as 1 | 2);
      km = r2(kmTo);
      d.sinceBreak += chunk;
      d.inShift += chunk;
      d.week += chunk;
      d.fortnight += chunk;
      left -= chunk;
    }
    if (leg.serviceMin) push('SERVICE', leg.serviceMin, li);
  });

  const sum = (kinds: TimelineKind[]) => timeline.filter((x) => kinds.includes(x.kind)).reduce((s, x) => s + x.minutes, 0);
  const durationMin = Math.round((now - startMs) / MIN);
  const days = Math.max(1, Math.ceil(durationMin / 1440));

  // --- costs -----------------------------------------------------------------------------------------
  const lines: CostLine[] = [];
  const totalKm = req.legs.reduce((s, l) => s + l.distanceKm, 0);
  const vehicle = req.vehicleId != null ? lk.vehicle(req.vehicleId) : undefined;
  const consumption = opts.consumptionL100km ?? vehicle?.avgL100km ?? null;
  const fuel = opts.fuelPrice != null ? { price: opts.fuelPrice, currency: opts.fuelCurrency ?? 'EUR' } : lk.dieselPrice();
  if (consumption == null) missing.push('calc.missing.consumption');
  if (!fuel) missing.push('calc.missing.fuel_price');
  if (consumption != null && !fuel) {
    lines.push({ kind: 'FUEL', label: null, country: null, quantity: r2((totalKm * consumption) / 100), unit: 'L', rate: null, amount: null, currency: null, source: 'MISSING', priceDate: null });
  }
  if (consumption != null && fuel) {
    const litres = r2((totalKm * consumption) / 100);
    // Typed-in price: MANUAL; else the firma's own card purchases (the mock has no reference prices).
    const source = opts.fuelPrice != null ? 'MANUAL' : 'OWN_TX_ALL';
    const priceDate = opts.fuelPrice != null ? null : req.start.slice(0, 10);
    lines.push({ kind: 'FUEL', label: null, country: null, quantity: litres, unit: 'L', rate: fuel.price, amount: r2(litres * fuel.price), currency: fuel.currency, source, priceDate });
  }

  // km per country over all legs.
  const byCountry = new Map<string, number>();
  // Tolls are paid on the toll km of a country when the route knows them, else on all its km.
  const tollKmBy = new Map<string, number>();
  for (const l of req.legs) {
    const parts = l.countries?.length ? l.countries : [{ country: lk.firmaCountry, km: l.distanceKm, tollKm: null }];
    for (const p of parts) {
      byCountry.set(p.country, (byCountry.get(p.country) ?? 0) + p.km);
      tollKmBy.set(p.country, (tollKmBy.get(p.country) ?? 0) + (p.tollKm ?? p.km));
    }
  }

  const abroad = [...byCountry.keys()].some((c) => c !== lk.firmaCountry);
  const payHidden = !lk.canSeePay;
  if (!payHidden) {
    const day = req.start.slice(0, 10);
    req.driverIds.forEach((id, i) => {
      const rate = lk.payRate(id, day);
      if (!rate) {
        if (!missing.includes('calc.missing.pay_rate')) missing.push('calc.missing.pay_rate');
        return;
      }
      const driven = timeline.filter((x) => x.kind === 'DRIVE' && x.driver === i + 1).reduce((s, x) => s + (x.kmTo - x.kmFrom), 0);
      const label = `#${i + 1}`;
      if (rate.perKm != null) lines.push({ kind: 'DRIVER_KM', label, country: null, quantity: r2(driven), unit: 'KM', rate: rate.perKm, amount: r2(driven * rate.perKm), currency: rate.currency });
      const perDiem = abroad ? rate.perDiemAbroad : rate.perDiemHome;
      if (perDiem != null) lines.push({ kind: 'DRIVER_PER_DIEM', label, country: null, quantity: days, unit: 'DAY', rate: perDiem, amount: r2(days * perDiem), currency: rate.currency });
      if (rate.monthlyBase != null) {
        const daily = r2(rate.monthlyBase / 30);
        lines.push({ kind: 'DRIVER_BASE', label, country: null, quantity: days, unit: 'DAY', rate: daily, amount: r2(days * daily), currency: rate.currency });
      }
    });
  }

  for (const [country] of byCountry) {
    const ckm = tollKmBy.get(country) ?? 0;
    const candidates = tariffs.filter((t) => t.country === country)
      .filter((t) => (!t.euroClass || t.euroClass === vehicle?.euroClass) && (t.axlesMin == null || (vehicle?.axles ?? 0) >= t.axlesMin) && (t.weightMinKg == null || (vehicle?.grossWeightKg ?? 0) >= t.weightMinKg));
    const best = candidates.sort((a, b) => [b.euroClass, b.axlesMin, b.weightMinKg].filter((x) => x != null).length - [a.euroClass, a.axlesMin, a.weightMinKg].filter((x) => x != null).length)[0];
    if (!best) {
      if (!missing.includes('calc.missing.toll_tariff')) missing.push('calc.missing.toll_tariff');
      lines.push({ kind: 'TOLL', label: null, country, quantity: r2(ckm), unit: 'KM', rate: null, amount: null, currency: null });
      continue;
    }
    lines.push({ kind: 'TOLL', label: null, country, quantity: r2(ckm), unit: 'KM', rate: best.ratePerKm, amount: r2(ckm * best.ratePerKm), currency: best.currency });
  }
  for (const o of opts.otherCosts ?? []) lines.push({ kind: 'OTHER', label: o.label, country: null, quantity: null, unit: null, rate: null, amount: r2(o.amount), currency: o.currency });

  const totals = new Map<string, number>();
  for (const l of lines) if (l.currency && l.amount != null) totals.set(l.currency, r2((totals.get(l.currency) ?? 0) + l.amount));

  const body: CalcPlan = {
    start: req.start,
    eta: new Date(now).toISOString(),
    totals: {
      distanceKm: r2(totalKm),
      drivingMin: sum(['DRIVE']),
      breakMin: sum(['BREAK']),
      restMin: sum(['DAILY_REST', 'REDUCED_DAILY_REST', 'WEEKLY_REST', 'REDUCED_WEEKLY_REST']),
      workMin: sum(['SERVICE']),
      durationMin,
      dailyRests: timeline.filter((x) => x.kind === 'DAILY_REST' || x.kind === 'REDUCED_DAILY_REST').length,
      weeklyRests: timeline.filter((x) => x.kind === 'WEEKLY_REST' || x.kind === 'REDUCED_WEEKLY_REST').length,
      days,
    },
    timeline,
    driverStates: states,
    costs: { lines, totals: [...totals].map(([currency, amount]) => ({ currency, amount })), missing },
    payHidden,
    warnings,
  };
  return { status: 200, body };
}

// --- toll tariffs ------------------------------------------------------------------------------------

export function listTolls(): MockReply {
  return { status: 200, body: [...tariffs].sort((a, b) => a.country.localeCompare(b.country) || b.ratePerKm - a.ratePerKm) };
}

export function saveToll(b: TollTariffInput, id: string | null): MockReply {
  const target = id == null ? undefined : tariffs.find((t) => String(t.id) === id);
  if (id != null && !target) return { status: 404, body: { code: 'error.not_found' } };
  const errors: Record<string, string> = {};
  if (!b.country) errors.country = 'validation.required';
  if (b.ratePerKm == null) errors.ratePerKm = 'validation.required';
  else if (b.ratePerKm < 0) errors.ratePerKm = 'validation.invalid_value';
  if (!/^[A-Z]{3}$/.test((b.currency ?? '').toUpperCase())) errors.currency = b.currency ? 'validation.invalid_format' : 'validation.required';
  if (Object.keys(errors).length) return invalid(errors);
  const value = { ...b, currency: b.currency.toUpperCase(), notes: b.notes?.trim() || null };
  if (target) {
    Object.assign(target, value);
    return { status: 200, body: target };
  }
  const created: TollTariff = { ...value, id: ++nextId };
  tariffs.push(created);
  return { status: 201, body: created };
}

export function removeToll(id: string): MockReply {
  const i = tariffs.findIndex((t) => String(t.id) === id);
  if (i < 0) return { status: 404, body: { code: 'error.not_found' } };
  tariffs.splice(i, 1);
  return { status: 204 };
}
