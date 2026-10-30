import type { AdminUser, AdminUserCreate, AdminUserUpdate, PasswordChange, Role, User, UserSettingsPatch } from '@/app/types';
import { ASSIGNABLE_ROLES } from '@/app/types';
import { isLanguageCode, type LanguageCode } from '@/i18n/languages';
import type { Dictionary } from '@/api/i18n';
import type { VehicleInput } from '@/api/fleet';
import { MOCK_FIRMA, MOCK_USERS, ROLE_PRIVILEGES, type MockUser } from './data';
import { applyVehicleInput, MOCK_VEHICLES, toVehicle, validateVehicle, type MockVehicle } from './fleet';

/*
 * In-browser mock of the backend's /app endpoints. Enabled with VITE_API_MOCK=true (default in dev).
 * The session lives in sessionStorage, per-user settings in localStorage; users live in memory per page load.
 */

interface MockRequest {
  method: string;
  path: string;
  params: Record<string, string>;
  body: unknown;
}

interface MockResponse {
  status: number;
  body?: unknown;
}

type Handler = (req: MockRequest) => MockResponse | Promise<MockResponse>;

interface Route {
  method: string;
  pattern: RegExp;
  keys: string[];
  handler: Handler;
}

const SESSION_KEY = 'rutera.mock.session';
const SETTINGS_KEY = 'rutera.mock.settings';

const routes: Route[] = [];
const users: MockUser[] = MOCK_USERS.map((u) => ({ ...u }));

function route(method: string, path: string, handler: Handler): void {
  const keys: string[] = [];
  const source = path.replace(/:([A-Za-z]+)/g, (_m, k: string) => {
    keys.push(k);
    return '([^/]+)';
  });
  routes.push({ method, pattern: new RegExp(`^${source}$`), keys, handler });
}

const error = (status: number, code: string, details?: Record<string, string>): MockResponse => ({
  status,
  body: details ? { code, details } : { code },
});

function readSettings(): Record<string, UserSettingsPatch> {
  try {
    return JSON.parse(localStorage.getItem(SETTINGS_KEY) ?? '{}') as Record<string, UserSettingsPatch>;
  } catch {
    return {};
  }
}

/** Same as AppUser.getFullName on the backend: "First Last". */
function fullName(u: MockUser): string {
  return `${u.firstName} ${u.lastName}`.trim();
}

function toUser(u: MockUser): User {
  const saved = readSettings()[String(u.id)] ?? {};
  return {
    id: u.id,
    login: u.login,
    name: fullName(u),
    role: u.role,
    firma: MOCK_FIRMA,
    language: saved.language ?? u.language,
    startScreen: saved.startScreen ?? null,
    privileges: ROLE_PRIVILEGES[u.role],
  };
}

function toAdminUser(u: MockUser): AdminUser {
  return {
    id: u.id,
    login: u.login,
    firstName: u.firstName,
    lastName: u.lastName,
    name: fullName(u),
    role: u.role,
    customRoleId: null,
    phone: u.phone,
    language: u.language,
    active: u.active,
    locked: u.locked,
    failedLogins: u.failedLogins,
    createdAt: u.createdAt,
    updatedAt: u.updatedAt,
  };
}

function currentMockUser(): MockUser | null {
  const id = sessionStorage.getItem(SESSION_KEY);
  return id ? (users.find((u) => String(u.id) === id && !u.deleted) ?? null) : null;
}

function withAuth(fn: (user: MockUser, req: MockRequest) => MockResponse | Promise<MockResponse>): Handler {
  return (req) => {
    const u = currentMockUser();
    return u ? fn(u, req) : error(401, 'auth.unauthenticated');
  };
}

function withPrivilege(
  key: string,
  fn: (user: MockUser, req: MockRequest) => MockResponse | Promise<MockResponse>,
): Handler {
  return withAuth((u, req) =>
    u.role === 'SUPERUSER' || ROLE_PRIVILEGES[u.role].includes(key) ? fn(u, req) : error(403, 'auth.access_denied'),
  );
}

function passwordError(value: unknown): string | null {
  if (typeof value !== 'string' || value.length === 0) return 'validation.required';
  if (value.length < 10) return 'validation.too_short';
  if (value.length > 72) return 'validation.too_long';
  return null;
}

// Dictionaries are lazy chunks: only the requested language is loaded.
const locales = import.meta.glob<{ default: Dictionary }>('/src/i18n/locales/*.json');

route('POST', '/app/auth/login', ({ body }) => {
  const { login, password } = (body ?? {}) as Partial<{ login: string; password: string }>;
  const u = users.find((x) => x.login === String(login ?? '').trim().toLowerCase() && !x.deleted);
  if (!u || u.password !== password) return error(401, 'auth.login.invalid');
  if (u.locked) return error(401, 'auth.login.locked');
  if (!u.active) return error(401, 'auth.login.disabled');
  sessionStorage.setItem(SESSION_KEY, String(u.id));
  return { status: 200, body: toUser(u) };
});

route('POST', '/app/auth/logout', () => {
  sessionStorage.removeItem(SESSION_KEY);
  return { status: 204 };
});

route('GET', '/app/auth/me', withAuth((user) => ({ status: 200, body: toUser(user) })));

route('PUT', '/app/me/settings', withAuth((u, { body }) => {
  const patch = (body ?? {}) as UserSettingsPatch;
  if (patch.language !== undefined && !isLanguageCode(patch.language)) {
    return error(422, 'error.validation', { language: 'validation.invalid_value' });
  }
  const all = readSettings();
  all[String(u.id)] = { ...all[String(u.id)], ...patch };
  localStorage.setItem(SETTINGS_KEY, JSON.stringify(all));
  return { status: 200, body: toUser(u) };
}));

route('PUT', '/app/me/password', withAuth((u, { body }) => {
  const { currentPassword, newPassword } = (body ?? {}) as Partial<PasswordChange>;
  if (currentPassword !== u.password) {
    return error(422, 'error.validation', { currentPassword: 'auth.password.wrong_current' });
  }
  const problem = passwordError(newPassword);
  if (problem) return error(422, 'error.validation', { newPassword: problem });
  u.password = newPassword as string;
  return { status: 204 };
}));

route('GET', '/app/i18n/:lang', async ({ params }) => {
  const loader = locales[`/src/i18n/locales/${params.lang}.json`];
  if (!loader) return error(404, 'error.not_found');
  const mod = await loader();
  return { status: 200, body: mod.default };
});

// --- fleet ---------------------------------------------------------------------------------------

const vehicles: MockVehicle[] = MOCK_VEHICLES.map((v) => ({ ...v, documents: [...v.documents] }));

function findVehicle(id: string): MockVehicle | undefined {
  return vehicles.find((x) => String(x.id) === id && !x.deleted);
}

route('GET', '/app/fleet/vehicles', withPrivilege('fleet.vehicle/view', () => ({
  status: 200,
  body: vehicles
    .filter((v) => !v.deleted)
    .map((v) => toVehicle(v, vehicles))
    .sort((a, b) => a.plate.localeCompare(b.plate)),
})));

route('GET', '/app/fleet/vehicles/:id', withPrivilege('fleet.vehicle/view', (_u, req) => {
  const v = findVehicle(req.params.id);
  return v ? { status: 200, body: toVehicle(v, vehicles) } : error(404, 'error.not_found');
}));

route('GET', '/app/fleet/vehicles/:id/documents', withPrivilege('fleet.vehicle/view', (_u, req) => {
  const v = findVehicle(req.params.id);
  return v ? { status: 200, body: v.documents } : error(404, 'error.not_found');
}));

route('POST', '/app/fleet/vehicles', withPrivilege('fleet.vehicle/manage', (_u, { body }) => {
  const b = (body ?? {}) as VehicleInput;
  const details = validateVehicle(b, vehicles, null);
  if (Object.keys(details).length > 0) return error(422, 'error.validation', details);
  const now = new Date().toISOString();
  const created: MockVehicle = {
    ...b,
    id: Math.max(...vehicles.map((x) => x.id)) + 1,
    lastVuDownloadAt: null,
    deleted: false,
    documents: [],
    createdAt: now,
    updatedAt: now,
  };
  applyVehicleInput(created, b);
  vehicles.push(created);
  return { status: 201, body: toVehicle(created, vehicles) };
}));

route('PUT', '/app/fleet/vehicles/:id', withPrivilege('fleet.vehicle/manage', (_u, req) => {
  const target = findVehicle(req.params.id);
  if (!target) return error(404, 'error.not_found');
  const b = (req.body ?? {}) as VehicleInput;
  const details = validateVehicle(b, vehicles, target.id);
  if (Object.keys(details).length > 0) return error(422, 'error.validation', details);
  applyVehicleInput(target, b);
  return { status: 200, body: toVehicle(target, vehicles) };
}));

route('DELETE', '/app/fleet/vehicles/:id', withPrivilege('fleet.vehicle/manage', (_u, req) => {
  const target = findVehicle(req.params.id);
  if (!target) return error(404, 'error.not_found');
  target.deleted = true;
  for (const v of vehicles) {
    if (v.defaultTrailerId === target.id) v.defaultTrailerId = null;
  }
  return { status: 204 };
}));

// --- user administration -------------------------------------------------------------------------

const LOGIN_RE = /^[\p{L}\p{N}._@+-]{3,254}$/u;

function findUser(id: string): MockUser | undefined {
  return users.find((x) => String(x.id) === id && !x.deleted);
}

route('GET', '/app/admin/users', withPrivilege('admin.user/view', () => ({
  status: 200,
  body: users
    .filter((u) => !u.deleted)
    .sort((a, b) => a.lastName.localeCompare(b.lastName) || a.firstName.localeCompare(b.firstName))
    .map(toAdminUser),
})));

route('GET', '/app/admin/users/:id', withPrivilege('admin.user/view', (_u, req) => {
  const target = findUser(req.params.id);
  return target ? { status: 200, body: toAdminUser(target) } : error(404, 'error.not_found');
}));

route('POST', '/app/admin/users', withPrivilege('admin.user/manage', (_u, { body }) => {
  const b = (body ?? {}) as Partial<AdminUserCreate>;
  const details: Record<string, string> = {};
  const login = String(b.login ?? '').trim().toLowerCase();
  if (!login) details.login = 'validation.required';
  else if (!LOGIN_RE.test(login)) details.login = 'validation.invalid_format';
  else if (users.some((x) => x.login === login)) details.login = 'admin.user.login_taken';
  if (!b.firstName?.trim()) details.firstName = 'validation.required';
  if (!b.role) details.role = 'validation.required';
  else if (!ASSIGNABLE_ROLES.includes(b.role)) details.role = 'validation.invalid_value';
  if (b.language != null && !isLanguageCode(b.language)) details.language = 'validation.invalid_value';
  const problem = passwordError(b.password);
  if (problem) details.password = problem;
  if (Object.keys(details).length > 0) return error(422, 'error.validation', details);
  const now = new Date().toISOString();
  const created: MockUser = {
    id: Math.max(...users.map((x) => x.id)) + 1,
    login,
    password: b.password as string,
    firstName: (b.firstName as string).trim(),
    lastName: (b.lastName ?? '').trim(),
    role: b.role as Role,
    language: b.language && isLanguageCode(b.language) ? b.language : 'pl',
    phone: b.phone?.trim() || null,
    active: true,
    locked: false,
    failedLogins: 0,
    deleted: false,
    createdAt: now,
    updatedAt: now,
  };
  users.push(created);
  return { status: 201, body: toAdminUser(created) };
}));

route('PUT', '/app/admin/users/:id', withPrivilege('admin.user/manage', (me, req) => {
  const target = findUser(req.params.id);
  if (!target) return error(404, 'error.not_found');
  const b = (req.body ?? {}) as Partial<AdminUserUpdate>;
  const details: Record<string, string> = {};
  if (!b.firstName?.trim()) details.firstName = 'validation.required';
  if (!b.role) details.role = 'validation.required';
  else if (!ASSIGNABLE_ROLES.includes(b.role) && b.role !== target.role) details.role = 'validation.invalid_value';
  if (!isLanguageCode(b.language)) details.language = 'validation.invalid_value';
  if (Object.keys(details).length > 0) return error(422, 'error.validation', details);
  if (target.id === me.id && (b.role !== me.role || b.active === false)) {
    return error(403, 'admin.user.self_forbidden');
  }
  target.firstName = (b.firstName as string).trim();
  target.lastName = (b.lastName ?? '').trim();
  target.role = b.role as Role;
  target.phone = b.phone?.trim() || null;
  target.language = b.language as LanguageCode;
  target.active = b.active !== false;
  target.updatedAt = new Date().toISOString();
  return { status: 200, body: toAdminUser(target) };
}));

route('PUT', '/app/admin/users/:id/password', withPrivilege('admin.user/manage', (me, req) => {
  const target = findUser(req.params.id);
  if (!target) return error(404, 'error.not_found');
  if (target.id === me.id) return error(403, 'admin.user.self_forbidden');
  const { newPassword } = (req.body ?? {}) as { newPassword?: string };
  const problem = passwordError(newPassword);
  if (problem) return error(422, 'error.validation', { newPassword: problem });
  target.password = newPassword as string;
  target.locked = false;
  target.failedLogins = 0;
  return { status: 204 };
}));

route('POST', '/app/admin/users/:id/unlock', withPrivilege('admin.user/manage', (_me, req) => {
  const target = findUser(req.params.id);
  if (!target) return error(404, 'error.not_found');
  target.locked = false;
  target.failedLogins = 0;
  return { status: 204 };
}));

route('DELETE', '/app/admin/users/:id', withPrivilege('admin.user/manage', (me, req) => {
  const target = findUser(req.params.id);
  if (!target) return error(404, 'error.not_found');
  if (target.id === me.id) return error(403, 'admin.user.self_forbidden');
  target.deleted = true;
  return { status: 204 };
}));

function toResponse(r: MockResponse): Response {
  if (r.body === undefined) return new Response(null, { status: r.status });
  return new Response(JSON.stringify(r.body), {
    status: r.status,
    headers: { 'Content-Type': 'application/json' },
  });
}

const delay = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms));

export async function mockFetch(input: string, init: RequestInit = {}): Promise<Response> {
  const path = input.replace(/^https?:\/\/[^/]+/, '').split('?')[0];
  const method = (init.method ?? 'GET').toUpperCase();
  await delay(120 + Math.random() * 180);
  for (const r of routes) {
    if (r.method !== method) continue;
    const m = r.pattern.exec(path);
    if (!m) continue;
    const params: Record<string, string> = {};
    r.keys.forEach((k, i) => {
      params[k] = decodeURIComponent(m[i + 1]);
    });
    const body = typeof init.body === 'string' && init.body ? (JSON.parse(init.body) as unknown) : undefined;
    return toResponse(await r.handler({ method, path, params, body }));
  }
  return toResponse(error(404, 'error.not_found'));
}

console.info('[RUTERA] Mock API enabled (VITE_API_MOCK=true). Test accounts: see frontend/README.md');
