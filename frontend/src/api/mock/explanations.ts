import type { Art12Claim, Explanation, ExplanationInput, ExplanationReason, ReasonOption, SignInput } from '@/api/explanations';
import { EXPLANATION_REASONS } from '@/api/explanations';
import type { Violation } from '@/api/hos';
import { sketchPdf } from './cmr';
import type { MockReply } from './trips';

/*
 * Driver explanations for the in-browser mock API (proposed contract in src/api/explanations.ts): opened from the
 * violations of the last days, signed by the driver with a reason and a finger signature, a sketch PDF.
 */

let store: Explanation[] | null = null;
let nextId = 1;

// Daily and weekly driving may be extended to reach home or the base (Art. 12 paragraphs 2 and 3).
const HOME_RULES = ['DAILY_DRIVING_9H', 'DAILY_DRIVING_10H', 'WEEKLY_DRIVING_56H'];
// Reasons inspectors accept as Art. 12 paragraph 1 grounds (research list of the backend templates).
const SAFETY_REASONS = ['TRAFFIC_ACCIDENT', 'ROAD_CLOSURE_DETOUR', 'EXTREME_WEATHER', 'NO_PARKING_SPACE', 'SECURITY_THREAT', 'VEHICLE_BREAKDOWN', 'LOAD_SAFETY', 'MEDICAL_EMERGENCY', 'AUTHORITY_ORDER', 'FERRY_TRAIN_DELAY'];
const TEXT_REASONS = ['NO_PARKING_SPACE', 'DATA_DISPUTE', 'OTHER'];

const allowed = (ruleCode: string) => ({ safety: true, home: HOME_RULES.includes(ruleCode) });
const art12Fields = (ruleCode: string) => ({
  infringementCode: ruleCode,
  art12: null,
  art12Place: null,
  art12BreakFrom: null,
  art12BreakTo: null,
  printoutNote: null,
  printoutNoteAt: null,
  art12Allowed: allowed(ruleCode),
});

/** GET /app/explanations/reasons: the reasons with the Art. 12 claims they support for this rule. */
export function reasons(ruleCode: string | undefined): MockReply {
  const home = !ruleCode || HOME_RULES.includes(ruleCode);
  const body: ReasonOption[] = EXPLANATION_REASONS.map((code) => ({
    code,
    art12Safety: SAFETY_REASONS.includes(code),
    art12Home: home && code === 'REACH_HOME_OR_BASE',
    textRequired: TEXT_REASONS.includes(code),
  }));
  return { status: 200, body };
}

/** GET /app/explanations/{id}/printout-note: the Polish note to copy onto the printout; unknowns as dotted blanks. */
export function printoutNote(violations: Violation[], id: string, q: Record<string, string>): MockReply {
  const e = explanations(violations).find((x) => String(x.id) === id);
  if (!e) return fail(404, 'error.not_found');
  const blank = '............';
  const place = q.place || blank;
  const text =
    q.art12 === 'SAFETY'
      ? `Art. 12 rozp. 561/2006 - przekroczenie w celu dotarcia do odpowiedniego miejsca postoju: ${place}. Przyczyna: ${q.reason ?? blank}.`
      : q.art12 === 'HOME_2H'
        ? `Art. 12 akapit 3 rozp. 561/2006 - powrót do bazy/miejsca zamieszkania: ${place}. Przerwa ${q.breakFrom || '..:..'}-${q.breakTo || '..:..'}.`
        : `Art. 12 akapit 2 rozp. 561/2006 - powrót do bazy/miejsca zamieszkania: ${place}.`;
  return { status: 200, body: { lang: 'pl', instruction: 'Przepisz odręcznie na wydruk z tachografu, z datą i podpisem.', text } };
}

const fail = (status: number, code: string, details?: Record<string, string>): MockReply => ({ status, body: { status, code, message: code, ...(details ? { details } : {}) } });

/** Built on first use: a pending explanation for each of the newest unreviewed violations, one already signed. */
function explanations(violations: Violation[]): Explanation[] {
  if (!store) {
    const recent = [...violations].sort((a, b) => b.startAt.localeCompare(a.startAt)).filter((v) => !v.ack).slice(0, 6);
    store = recent.map((v, i) => fromViolation(v, i === recent.length - 1 ? 'SIGNED' : 'PENDING'));
  }
  return store;
}

function fromViolation(v: Violation, status: Explanation['status'] = 'PENDING'): Explanation {
  const signed = status === 'SIGNED';
  return {
    id: nextId++,
    status,
    source: 'AUTO',
    driver: v.driver,
    vehicle: v.vehicle?.id != null ? { id: v.vehicle.id, plate: v.vehicle.plate } : null,
    violationId: v.id,
    ruleCode: v.rule,
    startedAt: v.startAt,
    endedAt: v.endAt,
    actualMin: v.actualValue,
    limitMin: v.limitValue,
    exceededMin: Math.max(0, Math.abs(v.actualValue - v.limitValue)),
    reason: signed ? 'TRAFFIC_JAM' : null,
    reasonText: signed ? 'Korek na A4 przed Legnicą po wypadku, brak wolnych miejsc na parkingu MOP.' : null,
    place: signed ? 'Legnica' : null,
    ...art12Fields(v.rule),
    docLang: 'pl',
    signedAt: signed ? new Date(new Date(v.endAt).getTime() + 3_600_000).toISOString() : null,
    signedBy: signed ? v.driver : null,
    cancelledAt: null,
    createdAt: v.endAt,
    createdBy: null,
    hasPdf: signed,
  };
}

/** The explanation of a violation, for its card (proposed fields of the violation row). */
export function ofViolation(violations: Violation[], violationId: number): { explanationId: number | null; explanationStatus: Explanation['status'] | null } {
  const e = explanations(violations).find((x) => x.violationId === violationId && x.status !== 'CANCELLED');
  return { explanationId: e?.id ?? null, explanationStatus: e?.status ?? null };
}

export function list(violations: Violation[], q: Record<string, string>, ownDriver: number | null): MockReply {
  const page = Number(q.page ?? 0);
  const size = Number(q.size ?? 50);
  const items = explanations(violations)
    .filter((e) => ownDriver == null || e.driver.id === ownDriver)
    .filter((e) => !q.driverId || String(e.driver.id) === q.driverId)
    .filter((e) => !q.status || e.status === q.status)
    .filter((e) => !q.from || e.startedAt.slice(0, 10) >= q.from)
    .filter((e) => !q.to || e.startedAt.slice(0, 10) <= q.to)
    .sort((a, b) => b.startedAt.localeCompare(a.startedAt));
  return { status: 200, body: { items: items.slice(page * size, (page + 1) * size), total: items.length } };
}

export function get(violations: Violation[], id: string, ownDriver: number | null): MockReply {
  const e = explanations(violations).find((x) => String(x.id) === id);
  if (!e || (ownDriver != null && e.driver.id !== ownDriver)) return fail(404, 'error.not_found');
  return { status: 200, body: e };
}

export function create(
  violations: Violation[],
  b: Partial<ExplanationInput> & Record<string, unknown>,
  lookup: { driver(id: number): { id: number; name: string } | undefined; vehicle(id: number): { id: number; plate: string } | undefined },
  by: { id: number; name: string },
): MockReply {
  const all = explanations(violations);
  if ('violationId' in b && b.violationId != null) {
    const v = violations.find((x) => x.id === Number(b.violationId));
    if (!v) return fail(422, 'error.validation', { violationId: 'validation.invalid_value' });
    // A violation has one explanation: it comes back, reopened when it was cancelled.
    const known = all.find((e) => e.violationId === v.id);
    if (known) {
      if (known.status === 'CANCELLED') Object.assign(known, { status: 'PENDING', cancelledAt: null });
      return { status: 201, body: known };
    }
    const e: Explanation = { ...fromViolation(v), source: 'MANUAL', createdBy: by, createdAt: new Date().toISOString() };
    all.unshift(e);
    return { status: 201, body: e };
  }
  const errors: Record<string, string> = {};
  const driver = typeof b.driverId === 'number' ? lookup.driver(b.driverId) : undefined;
  if (!driver) errors.driverId = 'validation.required';
  if (!b.ruleCode) errors.ruleCode = 'validation.required';
  if (!b.startedAt) errors.startedAt = 'validation.required';
  if (Object.keys(errors).length) return fail(422, 'error.validation', errors);
  if (all.some((x) => x.driver.id === driver!.id && x.ruleCode === b.ruleCode && x.startedAt === b.startedAt)) return fail(409, 'error.conflict');
  const vehicle = typeof b.vehicleId === 'number' ? (lookup.vehicle(b.vehicleId) ?? null) : null;
  const e: Explanation = {
    id: nextId++,
    status: 'PENDING',
    source: 'MANUAL',
    driver: driver!,
    vehicle,
    violationId: null,
    ruleCode: b.ruleCode as Explanation['ruleCode'],
    startedAt: String(b.startedAt),
    endedAt: (b.endedAt as string | null) ?? null,
    actualMin: null,
    limitMin: null,
    exceededMin: typeof b.exceededMin === 'number' ? b.exceededMin : null,
    reason: null,
    reasonText: null,
    place: null,
    ...art12Fields(String(b.ruleCode)),
    docLang: 'pl',
    signedAt: null,
    signedBy: null,
    cancelledAt: null,
    createdAt: new Date().toISOString(),
    createdBy: by,
    hasPdf: false,
  };
  all.unshift(e);
  return { status: 201, body: e };
}

export function sign(violations: Violation[], id: string, b: Partial<SignInput>, ownDriver: number | null): MockReply {
  const e = explanations(violations).find((x) => String(x.id) === id);
  if (!e || (ownDriver != null && e.driver.id !== ownDriver)) return fail(404, 'error.not_found');
  if (e.status !== 'PENDING') return fail(409, 'explanation.not_pending');
  const errors: Record<string, string> = {};
  if (!b.reason || !EXPLANATION_REASONS.includes(b.reason as ExplanationReason)) errors.reason = 'validation.required';
  if (TEXT_REASONS.includes(b.reason ?? '') && !b.reasonText?.trim()) errors.reasonText = 'validation.required';
  const claim = (b.art12 ?? 'NONE') as Art12Claim;
  const over = e.exceededMin ?? 0;
  const ok =
    claim === 'NONE' ||
    (claim === 'SAFETY' && SAFETY_REASONS.includes(b.reason ?? '')) ||
    (claim === 'HOME_1H' && e.art12Allowed?.home && b.reason === 'REACH_HOME_OR_BASE' && over <= 60) ||
    (claim === 'HOME_2H' && e.art12Allowed?.home && b.reason === 'REACH_HOME_OR_BASE' && over <= 120);
  if (!ok) errors.art12 = 'validation.invalid_value';
  if (claim !== 'NONE' && !b.art12Place?.trim()) errors.art12Place = 'validation.required';
  if (claim !== 'NONE' && !b.printoutNote) errors.printoutNote = 'validation.required';
  if (claim === 'HOME_2H' && (!b.art12BreakFrom || !b.art12BreakTo)) errors.art12BreakFrom = 'validation.required';
  if ((b.reasonText ?? '').length > 1000) errors.reasonText = 'validation.too_long';
  if ((b.place ?? '').length > 100) errors.place = 'validation.too_long';
  if (!b.signature?.startsWith('data:image/png;base64,') || b.signature.length > 300 * 1024) errors.signature = 'validation.invalid_value';
  if (Object.keys(errors).length) return fail(422, 'error.validation', errors);
  Object.assign(e, {
    status: 'SIGNED', reason: b.reason, reasonText: b.reasonText?.trim() || null, place: b.place?.trim() || null,
    art12: claim, art12Place: b.art12Place?.trim() || null, art12BreakFrom: b.art12BreakFrom ?? null, art12BreakTo: b.art12BreakTo ?? null,
    printoutNote: claim === 'NONE' ? null : Boolean(b.printoutNote), printoutNoteAt: b.printoutNoteAt ?? null,
    signedAt: new Date().toISOString(), signedBy: e.driver, hasPdf: true,
  });
  return { status: 200, body: e };
}

export function cancel(violations: Violation[], id: string): MockReply {
  const e = explanations(violations).find((x) => String(x.id) === id);
  if (!e) return fail(404, 'error.not_found');
  if (e.status !== 'PENDING') return fail(409, 'explanation.not_pending');
  Object.assign(e, { status: 'CANCELLED', cancelledAt: new Date().toISOString() });
  return { status: 200, body: e };
}

/** Pushes the request to the driver again (the mock only answers). */
export function remind(violations: Violation[], id: string): MockReply {
  const e = explanations(violations).find((x) => String(x.id) === id);
  if (!e) return fail(404, 'error.not_found');
  if (e.status !== 'PENDING') return fail(409, 'explanation.not_pending');
  return { status: 200, body: e };
}

export function pdf(violations: Violation[], id: string, ownDriver: number | null): MockReply & { headers?: Record<string, string> } {
  const e = explanations(violations).find((x) => String(x.id) === id);
  if (!e || (ownDriver != null && e.driver.id !== ownDriver)) return fail(404, 'error.not_found');
  const lines = [
    'WYJASNIENIE KIEROWCY',
    `Kierowca: ${e.driver.name}`,
    `Pojazd: ${e.vehicle?.plate ?? '-'}`,
    `Naruszenie: ${e.ruleCode}, ${e.startedAt} - ${e.endedAt ?? ''}`,
    `Przekroczenie: ${e.exceededMin ?? '-'} min`,
    `Przyczyna: ${e.reason ?? '-'} ${e.reasonText ?? ''}`,
    `Miejsce: ${e.place ?? '-'}`,
    e.signedAt ? `Podpisano: ${e.signedAt}` : 'PROJEKT - NIE PODPISANO',
  ];
  return {
    status: 200,
    body: sketchPdf([lines]),
    headers: { 'Content-Type': 'application/pdf', 'Content-Disposition': `inline; filename*=UTF-8''${encodeURIComponent(`wyjasnienie-${e.id}.pdf`)}` },
  };
}
