import type { OperatorFirma, OperatorFirmaCreate, OperatorFirmaInput } from '@/api/operator';
import type { MockReply } from './trips';

/*
 * Operator console for the in-browser mock API, with the rules of OperatorService: list by id with search in name
 * and short code, PUT of all operator fields, 422 per field. Firma 1 is the mock firma of the other screens; its
 * options are written back through `onChange`, so Settings -> Company shows them.
 */

const PLAN = /^[A-Z0-9_]{1,32}$/;
const SHORT_CODE = /^[A-Z0-9_-]{2,32}$/;
const LOGIN = /^[\p{L}\p{N}._@+-]{3,254}$/u;
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

/** POST with the rules of the backend; the owner is not a mock login (the mock only checks that the login is free). */
export function create(b: Partial<OperatorFirmaCreate>, loginTaken: (login: string) => boolean): MockReply {
  const errors: Record<string, string> = {};
  const name = (b.name ?? '').trim();
  if (!name) errors.name = 'validation.required';
  else if (name.length > 200) errors.name = 'validation.too_long';
  const shortCode = (b.shortCode ?? '').trim().toUpperCase();
  if (!shortCode) errors.shortCode = 'validation.required';
  else if (!SHORT_CODE.test(shortCode)) errors.shortCode = 'validation.invalid_format';
  else if (firmas.some((f) => f.shortCode === shortCode)) errors.shortCode = 'operator.firma.short_code_taken';
  const country = (b.country ?? '').trim().toUpperCase();
  if (!country) errors.country = 'validation.required';
  else if (!/^[A-Z]{2}$/.test(country)) errors.country = 'validation.invalid_format';
  const plan = (b.plan ?? 'STANDARD').trim().toUpperCase();
  if (!PLAN.test(plan)) errors.plan = 'validation.invalid_value';
  const vehicleLimit = b.vehicleLimit ?? 10;
  if (vehicleLimit < 0 || vehicleLimit > MAX_VEHICLE_LIMIT) errors.vehicleLimit = 'validation.invalid_value';
  const login = (b.owner?.login ?? '').trim().toLowerCase();
  if (!login) errors['owner.login'] = 'validation.required';
  else if (!LOGIN.test(login)) errors['owner.login'] = 'validation.invalid_format';
  else if (loginTaken(login)) errors['owner.login'] = 'admin.user.login_taken';
  const firstName = (b.owner?.firstName ?? '').trim();
  if (!firstName) errors['owner.firstName'] = 'validation.required';
  else if (firstName.length > 100) errors['owner.firstName'] = 'validation.too_long';
  const password = b.owner?.password ?? null;
  if (password && password.length < 10) errors['owner.password'] = 'validation.too_short';
  else if (password && password.length > 72) errors['owner.password'] = 'validation.too_long';
  if (Object.keys(errors).length) return { status: 422, body: { status: 422, code: 'error.validation', message: 'error.validation', details: errors } };
  const firma: OperatorFirma = {
    id: Math.max(0, ...firmas.map((f) => f.id)) + 1,
    name,
    shortCode,
    country,
    plan,
    vehicleLimit,
    aiAccess: b.aiAccess ?? false,
    active: true,
    test: b.test ?? false,
    expiresAt: b.expiresAt ?? null,
    users: 1,
    vehicles: 0,
    createdAt: new Date().toISOString(),
  };
  firmas.push(firma);
  const generated = password ? null : Array.from(crypto.getRandomValues(new Uint8Array(16)), (x) => 'abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789'[x % 56]).join('');
  return { status: 201, body: { firma, ownerId: 1000 + firma.id, ownerLogin: login, password: generated } };
}
