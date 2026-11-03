import type { OperatorFirma, OperatorFirmaInput } from '@/api/operator';
import type { MockReply } from './trips';

/*
 * Operator console for the in-browser mock API, with the rules of OperatorService: list by id with search in name
 * and short code, PUT of all operator fields, 422 per field. Firma 1 is the mock firma of the other screens; its
 * options are written back through `onChange`, so Settings -> Company shows them.
 */

const PLAN = /^[A-Z0-9_]{1,32}$/;
const MAX_VEHICLE_LIMIT = 100_000;
const day = (n: number) => new Date(Date.now() + n * 86_400_000).toISOString();

const firmas: OperatorFirma[] = [];
let onChange: (f: OperatorFirma) => void = () => {};

/** Seeds the list once; `own` is the mock firma (id 1) of the other screens. */
export function initOperator(own: Pick<OperatorFirma, 'name' | 'shortCode' | 'country' | 'plan' | 'vehicleLimit' | 'aiAccess'>, changed: (f: OperatorFirma) => void) {
  onChange = changed;
  if (firmas.length) return;
  firmas.push(
    { id: 1, ...own, active: true, test: false, expiresAt: null, users: 5, vehicles: 8, createdAt: '2026-10-01T08:00:00Z' },
    { id: 2, name: 'Spedpol Logistyka Sp. z o.o.', shortCode: 'SPEDPOL', country: 'PL', plan: 'TRIAL', vehicleLimit: 5, aiAccess: false, active: true, test: false, expiresAt: day(9), users: 2, vehicles: 3, createdAt: day(-21) },
    { id: 3, name: 'Nordfracht GmbH', shortCode: 'NORDFR', country: 'DE', plan: 'STANDARD', vehicleLimit: 20, aiAccess: true, active: true, test: false, expiresAt: null, users: 7, vehicles: 22, createdAt: day(-120) },
    { id: 4, name: 'Logistica Iberia S.L.', shortCode: 'LOGIB', country: 'ES', plan: 'STANDARD', vehicleLimit: 10, aiAccess: false, active: true, test: false, expiresAt: day(-3), users: 3, vehicles: 6, createdAt: day(-400) },
    { id: 5, name: 'Demo Transport', shortCode: 'DEMO', country: 'PL', plan: 'PRO', vehicleLimit: 50, aiAccess: true, active: false, test: true, expiresAt: null, users: 1, vehicles: 4, createdAt: day(-60) },
  );
}

export function list(query: Record<string, string>): MockReply {
  const size = Number(query.size ?? 50);
  const page = Number(query.page ?? 0);
  if (page < 0 || size < 1 || size > 200) {
    return { status: 422, body: { status: 422, code: 'error.validation', message: 'error.validation', details: { [page < 0 ? 'page' : 'size']: 'validation.invalid_value' } } };
  }
  const q = (query.q ?? '').trim().toLowerCase();
  const found = firmas.filter((f) => !q || f.name.toLowerCase().includes(q) || f.shortCode.toLowerCase().includes(q));
  return { status: 200, body: { items: found.slice(page * size, page * size + size), total: found.length } };
}

export function get(id: string): MockReply {
  const f = firmas.find((x) => x.id === Number(id));
  return f ? { status: 200, body: f } : { status: 404, body: { status: 404, code: 'error.not_found', message: 'error.not_found' } };
}

export function update(id: string, b: Partial<OperatorFirmaInput>): MockReply {
  const f = firmas.find((x) => x.id === Number(id));
  if (!f) return { status: 404, body: { status: 404, code: 'error.not_found', message: 'error.not_found' } };
  const errors: Record<string, string> = {};
  const plan = (b.plan ?? '').trim().toUpperCase();
  if (!plan) errors.plan = 'validation.required';
  else if (!PLAN.test(plan)) errors.plan = 'validation.invalid_value';
  if (b.vehicleLimit == null) errors.vehicleLimit = 'validation.required';
  else if (b.vehicleLimit < 0 || b.vehicleLimit > MAX_VEHICLE_LIMIT) errors.vehicleLimit = 'validation.invalid_value';
  if (b.aiAccess == null) errors.aiAccess = 'validation.required';
  if (b.active == null) errors.active = 'validation.required';
  if (Object.keys(errors).length) return { status: 422, body: { status: 422, code: 'error.validation', message: 'error.validation', details: errors } };
  Object.assign(f, { plan, vehicleLimit: b.vehicleLimit, aiAccess: b.aiAccess, active: b.active, expiresAt: b.expiresAt ?? null });
  onChange(f);
  return { status: 200, body: f };
}
