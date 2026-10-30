import type { AdminUser, AdminUserCreate, AdminUserUpdate, PasswordChange, Role, User, UserSettingsPatch } from '@/app/types';
import { ASSIGNABLE_ROLES } from '@/app/types';
import { isLanguageCode, type LanguageCode } from '@/i18n/languages';
import type { Dictionary } from '@/api/i18n';
import type { DriverDocumentInput, DriverInput } from '@/api/drivers';
import type { VehicleDocumentInput, VehicleInput } from '@/api/fleet';
import { MOCK_FIRMA, MOCK_USERS, ROLE_PRIVILEGES, type MockUser } from './data';
import {
  applyVehicleInput,
  MOCK_VEHICLES,
  toDocument,
  toVehicle,
  validateDocument,
  validateVehicle,
  type MockVehicle,
} from './fleet';
import {
  applyDriverInput,
  MOCK_DRIVERS,
  toDriver,
  toDriverDocument,
  validateDriver,
  validateDriverDocument,
  type MockDriver,
} from './drivers';
import {
  buildFile,
  rejectReason,
  sha256Hex,
  toTachoDetail,
  toTachoFile,
  type MockCardOwner,
  type MockTachoFile,
  type MockUnitOwner,
} from './tacho';
import { TACHO_MAX_FILES, type TachoMatch } from '@/api/tacho';
import { isTrailer } from '@/api/fleet';
/*
 * In-browser mock of the backend's /app endpoints. Enabled with VITE_API_MOCK=true (default in dev).
 * The session lives in sessionStorage, per-user settings in localStorage; users live in memory per page load.
 */

interface MockRequest {
  method: string;
  path: string;
  params: Record<string, string>;
  /** Query string parameters. */
  query: Record<string, string>;
  body: unknown;
}

interface MockResponse {
  status: number;
  /** JSON-serialisable value, or a Blob sent as is (file downloads). */
  body?: unknown;
  headers?: Record<string, string>;
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
const drivers: MockDriver[] = MOCK_DRIVERS.map((d) => ({ ...d, documents: [...d.documents] }));

const liveDriver = (id: number) => drivers.find((d) => d.id === id && !d.deleted);
const driverName = (id: number) => {
  const d = liveDriver(id);
  return d ? `${d.firstName} ${d.lastName}`.trim() : null;
};
const driverExists = (id: number) => liveDriver(id) !== undefined;

function findVehicle(id: string): MockVehicle | undefined {
  return vehicles.find((x) => String(x.id) === id && !x.deleted);
}

route('GET', '/app/fleet/vehicles', withPrivilege('fleet.vehicle/view', () => ({
  status: 200,
  body: vehicles
    .filter((v) => !v.deleted)
    .map((v) => toVehicle(v, vehicles, driverName))
    .sort((a, b) => a.plate.localeCompare(b.plate)),
})));

route('GET', '/app/fleet/vehicles/:id', withPrivilege('fleet.vehicle/view', (_u, req) => {
  const v = findVehicle(req.params.id);
  return v ? { status: 200, body: toVehicle(v, vehicles, driverName) } : error(404, 'error.not_found');
}));

route('GET', '/app/fleet/vehicles/:id/documents', withPrivilege('fleet.vehicle/view', (_u, req) => {
  const v = findVehicle(req.params.id);
  if (!v) return error(404, 'error.not_found');
  const sorted = [...v.documents].sort((a, b) => (a.validTo ?? '9999').localeCompare(b.validTo ?? '9999'));
  return { status: 200, body: sorted };
}));

route('POST', '/app/fleet/vehicles/:id/documents', withPrivilege('fleet.document/manage', (_u, req) => {
  const v = findVehicle(req.params.id);
  if (!v) return error(404, 'error.not_found');
  const b = (req.body ?? {}) as VehicleDocumentInput;
  const details = validateDocument(b);
  if (Object.keys(details).length > 0) return error(422, 'error.validation', details);
  const created = toDocument(b, Math.max(0, ...vehicles.flatMap((x) => x.documents.map((d) => d.id))) + 1);
  v.documents.push(created);
  return { status: 201, body: created };
}));

route('PUT', '/app/fleet/vehicles/:id/documents/:docId', withPrivilege('fleet.document/manage', (_u, req) => {
  const v = findVehicle(req.params.id);
  const i = v ? v.documents.findIndex((d) => String(d.id) === req.params.docId) : -1;
  if (!v || i < 0) return error(404, 'error.not_found');
  const b = (req.body ?? {}) as VehicleDocumentInput;
  const details = validateDocument(b);
  if (Object.keys(details).length > 0) return error(422, 'error.validation', details);
  v.documents[i] = toDocument(b, v.documents[i].id);
  return { status: 200, body: v.documents[i] };
}));

route('DELETE', '/app/fleet/vehicles/:id/documents/:docId', withPrivilege('fleet.document/manage', (_u, req) => {
  const v = findVehicle(req.params.id);
  const i = v ? v.documents.findIndex((d) => String(d.id) === req.params.docId) : -1;
  if (!v || i < 0) return error(404, 'error.not_found');
  v.documents.splice(i, 1);
  return { status: 204 };
}));

route('POST', '/app/fleet/vehicles', withPrivilege('fleet.vehicle/manage', (_u, { body }) => {
  const b = (body ?? {}) as VehicleInput;
  const details = validateVehicle(b, vehicles, null, driverExists);
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
  return { status: 201, body: toVehicle(created, vehicles, driverName) };
}));

route('PUT', '/app/fleet/vehicles/:id', withPrivilege('fleet.vehicle/manage', (_u, req) => {
  const target = findVehicle(req.params.id);
  if (!target) return error(404, 'error.not_found');
  const b = (req.body ?? {}) as VehicleInput;
  const details = validateVehicle(b, vehicles, target.id, driverExists);
  if (Object.keys(details).length > 0) return error(422, 'error.validation', details);
  applyVehicleInput(target, b);
  return { status: 200, body: toVehicle(target, vehicles, driverName) };
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

// --- drivers -------------------------------------------------------------------------------------

function findDriver(id: string): MockDriver | undefined {
  return drivers.find((x) => String(x.id) === id && !x.deleted);
}

route('GET', '/app/drivers', withPrivilege('driver.driver/view', () => ({
  status: 200,
  body: drivers
    .filter((d) => !d.deleted)
    .sort((a, b) => a.lastName.localeCompare(b.lastName) || a.firstName.localeCompare(b.firstName))
    .map((d) => toDriver(d, vehicles)),
})));

route('GET', '/app/drivers/:id', withPrivilege('driver.driver/view', (_u, req) => {
  const d = findDriver(req.params.id);
  return d ? { status: 200, body: toDriver(d, vehicles) } : error(404, 'error.not_found');
}));

route('POST', '/app/drivers', withPrivilege('driver.driver/manage', (_u, { body }) => {
  const b = (body ?? {}) as DriverInput;
  const details = validateDriver(b, drivers, null);
  if (Object.keys(details).length > 0) return error(422, 'error.validation', details);
  const now = new Date().toISOString();
  const created: MockDriver = {
    ...b,
    id: Math.max(...drivers.map((x) => x.id)) + 1,
    user: null,
    card: null,
    deleted: false,
    documents: [],
    createdAt: now,
    updatedAt: now,
  };
  applyDriverInput(created, b);
  drivers.push(created);
  return { status: 201, body: toDriver(created, vehicles) };
}));

route('PUT', '/app/drivers/:id', withPrivilege('driver.driver/manage', (_u, req) => {
  const target = findDriver(req.params.id);
  if (!target) return error(404, 'error.not_found');
  const b = (req.body ?? {}) as DriverInput;
  const details = validateDriver(b, drivers, target.id);
  if (Object.keys(details).length > 0) return error(422, 'error.validation', details);
  applyDriverInput(target, b);
  return { status: 200, body: toDriver(target, vehicles) };
}));

route('DELETE', '/app/drivers/:id', withPrivilege('driver.driver/manage', (_u, req) => {
  const target = findDriver(req.params.id);
  if (!target) return error(404, 'error.not_found');
  target.deleted = true;
  for (const v of vehicles) {
    if (v.defaultDriverId === target.id) v.defaultDriverId = null;
  }
  return { status: 204 };
}));

route('GET', '/app/drivers/:id/documents', withPrivilege('driver.driver/view', (_u, req) => {
  const d = findDriver(req.params.id);
  if (!d) return error(404, 'error.not_found');
  const sorted = [...d.documents].sort((a, b) => (a.validTo ?? '9999').localeCompare(b.validTo ?? '9999'));
  return { status: 200, body: sorted };
}));

route('POST', '/app/drivers/:id/documents', withPrivilege('driver.driver/manage', (_u, req) => {
  const d = findDriver(req.params.id);
  if (!d) return error(404, 'error.not_found');
  const b = (req.body ?? {}) as DriverDocumentInput;
  const details = validateDriverDocument(b);
  if (Object.keys(details).length > 0) return error(422, 'error.validation', details);
  const created = toDriverDocument(b, Math.max(0, ...drivers.flatMap((x) => x.documents.map((y) => y.id))) + 1);
  d.documents.push(created);
  return { status: 201, body: created };
}));

route('PUT', '/app/drivers/:id/documents/:docId', withPrivilege('driver.driver/manage', (_u, req) => {
  const d = findDriver(req.params.id);
  const i = d ? d.documents.findIndex((x) => String(x.id) === req.params.docId) : -1;
  if (!d || i < 0) return error(404, 'error.not_found');
  const b = (req.body ?? {}) as DriverDocumentInput;
  const details = validateDriverDocument(b);
  if (Object.keys(details).length > 0) return error(422, 'error.validation', details);
  d.documents[i] = toDriverDocument(b, d.documents[i].id);
  return { status: 200, body: d.documents[i] };
}));

route('DELETE', '/app/drivers/:id/documents/:docId', withPrivilege('driver.driver/manage', (_u, req) => {
  const d = findDriver(req.params.id);
  const i = d ? d.documents.findIndex((x) => String(x.id) === req.params.docId) : -1;
  if (!d || i < 0) return error(404, 'error.not_found');
  d.documents.splice(i, 1);
  return { status: 204 };
}));

// --- tachograph files ---------------------------------------------------------------------------

const cardOwners = (): MockCardOwner[] =>
  drivers
    .filter((d) => !d.deleted && d.card)
    .map((d) => ({
      driverId: d.id,
      name: `${d.firstName} ${d.lastName}`,
      cardNumber: d.card!.cardNumber,
      issuingCountry: d.card!.issuingCountry,
    }));

const unitOwners = (): MockUnitOwner[] =>
  vehicles
    .filter((v) => !v.deleted && !isTrailer(v.kind) && v.tachoGen !== 'NONE')
    .map((v) => ({ vehicleId: v.id, plate: v.plate, vin: v.vin }));

const later = (a: string | null, b: string | null) => (a && b ? (a > b ? a : b) : (a ?? b));

/** lastDownloadAt of the driver card / vehicle unit = the newest download among the files assigned to it. */
function recomputeDownloads(): void {
  for (const d of drivers) {
    if (!d.card) continue;
    const newest = tachoFiles
      .filter((f) => f.driver?.id === d.id)
      .reduce<string | null>((acc, f) => later(acc, f.downloadedAt), null);
    d.card = { ...d.card, lastDownloadAt: newest };
  }
  for (const v of vehicles) {
    v.lastVuDownloadAt = tachoFiles
      .filter((f) => f.vehicle?.id === v.id)
      .reduce<string | null>((acc, f) => later(acc, f.downloadedAt), null);
  }
}

function assignFile(f: MockTachoFile, target: { driverId?: number; vehicleId?: number }, match: TachoMatch): string | null {
  if (f.kind === 'DRIVER_CARD') {
    const d = target.driverId == null ? undefined : liveDriver(target.driverId);
    if (!d) return 'driverId';
    f.driver = { id: d.id, name: `${d.firstName} ${d.lastName}` };
    if (!d.card && f.cardNumber) {
      // Assigning a card file to a driver without a card gives him the card from the file.
      d.card = { cardNumber: f.cardNumber, issuingCountry: f.cardIssuingCountry ?? 'PL', validTo: f.cardValidTo, lastDownloadAt: null };
    }
  } else {
    const v = target.vehicleId == null ? undefined : vehicles.find((x) => x.id === target.vehicleId && !x.deleted);
    if (!v || isTrailer(v.kind)) return 'vehicleId';
    f.vehicle = { id: v.id, plate: v.plate };
    if (v.tachoGen === 'NONE') v.tachoGen = f.generation === 'GEN1' ? 'GEN1' : 'GEN2';
  }
  f.match = match;
  recomputeDownloads();
  return null;
}

const MOCK_UPLOADER = { id: 1, name: 'Anna Kowalska' };
const tachoFiles: MockTachoFile[] = [
  ['C_20261012_1102_J_KOWALSKI_PL00000012345600.ddd', 'a1f3c0d2e4b5a6978877665544332211aabbccddeeff00112233445566778899'],
  ['M_20261001_0830_DW4471F_WMA06XZZ7NP123456.ddd', 'b2e4d1c3a5f6071829304152637485960a1b2c3d4e5f60718293a4b5c6d7e8f9'],
  ['card_unknown_driver.ddd', '0c11d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8091a2b'],
  ['V_unit_old_truck.v1b', 'd5c4b3a29180f7e6d5c4b3a291807f6e5d4c3b2a19087f6e5d4c3b2a1908f7e6'],
].map(([name, hash], i) => {
  const f = buildFile(i + 1, name, 25_000 + i * 7_000, hash, MOCK_UPLOADER, cardOwners(), unitOwners());
  f.uploadedAt = new Date(Date.now() - (4 - i) * 3_600_000).toISOString();
  return f;
});
recomputeDownloads();

const canReadTacho = (u: MockUser) =>
  u.role === 'SUPERUSER' || ['tacho.hos/view', 'tacho.file/upload'].some((k) => ROLE_PRIVILEGES[u.role].includes(k));

route('POST', '/app/tacho/files', withPrivilege('tacho.file/upload', async (u, { body }) => {
  const files = body instanceof FormData ? (body.getAll('files').filter((x) => x instanceof File) as File[]) : [];
  if (files.length === 0) return error(422, 'error.validation', { files: 'validation.required' });
  if (files.length > TACHO_MAX_FILES) return error(413, 'tacho.file.too_many');
  const uploader = { id: u.id, name: `${u.firstName} ${u.lastName}` };
  const results = [];
  for (const file of files) {
    const reason = rejectReason(file);
    if (reason) {
      results.push({ fileName: file.name, status: 'REJECTED', file: null, error: { code: reason } });
      continue;
    }
    const hash = await sha256Hex(file);
    const existing = tachoFiles.find((f) => f.sha256 === hash);
    if (existing) {
      results.push({ fileName: file.name, status: 'DUPLICATE', file: toTachoFile(existing), error: null });
      continue;
    }
    const id = Math.max(0, ...tachoFiles.map((f) => f.id)) + 1;
    const created = buildFile(id, file.name, file.size, hash, uploader, cardOwners(), unitOwners());
    created.bytes = file;
    tachoFiles.push(created);
    recomputeDownloads();
    results.push({ fileName: file.name, status: 'IMPORTED', file: toTachoFile(created), error: null });
  }
  return { status: 200, body: results };
}));

route('GET', '/app/tacho/files', withAuth((u, { query }) => {
  if (!canReadTacho(u)) return error(403, 'auth.access_denied');
  const page = Number(query.page ?? 0);
  const size = Number(query.size ?? 50);
  const items = tachoFiles
    .filter((f) => !query.kind || f.kind === query.kind)
    .filter((f) => !query.driverId || String(f.driver?.id) === query.driverId)
    .filter((f) => !query.vehicleId || String(f.vehicle?.id) === query.vehicleId)
    .filter((f) => query.unassigned !== 'true' || (!f.driver && !f.vehicle))
    // Same as the backend: files whose period overlaps from..to.
    .filter((f) => !query.from || (f.periodTo ?? f.periodFrom ?? '') >= query.from)
    .filter((f) => !query.to || (f.periodFrom ?? f.periodTo ?? '9999') <= query.to)
    .sort((a, b) => b.uploadedAt.localeCompare(a.uploadedAt) || b.id - a.id);
  return { status: 200, body: { items: items.slice(page * size, (page + 1) * size).map(toTachoFile), total: items.length } };
}));

const findTachoFile = (id: string) => tachoFiles.find((f) => String(f.id) === id);

route('GET', '/app/tacho/files/:id', withAuth((u, req) => {
  if (!canReadTacho(u)) return error(403, 'auth.access_denied');
  const f = findTachoFile(req.params.id);
  return f ? { status: 200, body: toTachoDetail(f) } : error(404, 'error.not_found');
}));

route('GET', '/app/tacho/files/:id/content', withPrivilege('tacho.file/upload', (_u, req) => {
  const f = findTachoFile(req.params.id);
  if (!f) return error(404, 'error.not_found');
  return {
    status: 200,
    body: f.bytes ?? new Blob([`mock DDD ${f.sha256}`], { type: 'application/octet-stream' }),
    headers: {
      'Content-Type': 'application/octet-stream',
      'Content-Disposition': `attachment; filename*=UTF-8''${encodeURIComponent(f.originalName)}`,
    },
  };
}));

route('PUT', '/app/tacho/files/:id/assignment', withPrivilege('tacho.file/upload', (_u, req) => {
  const f = findTachoFile(req.params.id);
  if (!f) return error(404, 'error.not_found');
  const b = (req.body ?? {}) as { driverId?: number; vehicleId?: number };
  const bad = assignFile(f, b, 'MANUAL');
  return bad ? error(422, 'error.validation', { [bad]: 'validation.invalid_value' }) : { status: 200, body: toTachoFile(f) };
}));

route('POST', '/app/tacho/files/:id/driver', withPrivilege('tacho.file/upload', (u, req) => {
  if (u.role !== 'SUPERUSER' && !ROLE_PRIVILEGES[u.role].includes('driver.driver/manage')) return error(403, 'auth.access_denied');
  const f = findTachoFile(req.params.id);
  if (!f) return error(404, 'error.not_found');
  const s = f.suggestion;
  if (f.kind !== 'DRIVER_CARD' || f.match !== 'NONE' || !s || !('cardNumber' in s)) return error(409, 'error.conflict');
  const now = new Date().toISOString();
  const created: MockDriver = {
    id: Math.max(...drivers.map((x) => x.id)) + 1,
    firstName: s.firstName ?? '',
    lastName: s.lastName ?? s.cardNumber,
    birthDate: s.birthDate,
    phone: null,
    email: null,
    country: s.cardIssuingCountry,
    employmentFrom: null,
    employmentTo: null,
    status: 'ACTIVE',
    notes: null,
    user: null,
    card: { cardNumber: s.cardNumber, issuingCountry: s.cardIssuingCountry, validTo: s.cardValidTo, lastDownloadAt: null },
    deleted: false,
    documents: [],
    createdAt: now,
    updatedAt: now,
  };
  drivers.push(created);
  assignFile(f, { driverId: created.id }, 'MANUAL');
  return { status: 201, body: { driver: toDriver(created, vehicles), file: toTachoFile(f) } };
}));

route('POST', '/app/tacho/files/:id/vehicle', withPrivilege('tacho.file/upload', (u, req) => {
  if (u.role !== 'SUPERUSER' && !ROLE_PRIVILEGES[u.role].includes('fleet.vehicle/manage')) return error(403, 'auth.access_denied');
  const f = findTachoFile(req.params.id);
  if (!f) return error(404, 'error.not_found');
  const s = f.suggestion;
  if (f.kind !== 'VEHICLE_UNIT' || f.match !== 'NONE' || !s || !('tachoGen' in s)) return error(409, 'error.conflict');
  const now = new Date().toISOString();
  const created: MockVehicle = {
    ...MOCK_VEHICLES[0],
    id: Math.max(...vehicles.map((x) => x.id)) + 1,
    kind: 'TRACTOR',
    plate: s.plate ?? s.vin ?? `NEW ${f.id}`,
    vin: s.vin,
    make: null,
    model: null,
    euroClass: null,
    axles: null,
    grossWeightKg: null,
    heightMm: null,
    avgL100km: null,
    fuelType: null,
    tachoGen: s.tachoGen,
    odometerKm: null,
    status: 'ACTIVE',
    defaultDriverId: null,
    defaultTrailerId: null,
    lastVuDownloadAt: null,
    deleted: false,
    documents: [],
    createdAt: now,
    updatedAt: now,
  };
  vehicles.push(created);
  assignFile(f, { vehicleId: created.id }, 'MANUAL');
  return { status: 201, body: { vehicle: toVehicle(created, vehicles, driverName), file: toTachoFile(f) } };
}));

route('DELETE', '/app/tacho/files/:id', withPrivilege('tacho.file/upload', (_u, req) => {
  const i = tachoFiles.findIndex((f) => String(f.id) === req.params.id);
  if (i < 0) return error(404, 'error.not_found');
  tachoFiles.splice(i, 1);
  recomputeDownloads();
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
  if (r.body instanceof Blob) return new Response(r.body, { status: r.status, headers: r.headers });
  return new Response(JSON.stringify(r.body), {
    status: r.status,
    headers: { 'Content-Type': 'application/json' },
  });
}

const delay = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms));

export async function mockFetch(input: string, init: RequestInit = {}): Promise<Response> {
  const path = input.replace(/^https?:\/\/[^/]+/, '').split('?')[0];
  const query = Object.fromEntries(new URL(input, 'http://mock').searchParams);
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
    const body =
      init.body instanceof FormData
        ? init.body
        : typeof init.body === 'string' && init.body
          ? (JSON.parse(init.body) as unknown)
          : undefined;
    return toResponse(await r.handler({ method, path, params, query, body }));
  }
  return toResponse(error(404, 'error.not_found'));
}

console.info('[RUTERA] Mock API enabled (VITE_API_MOCK=true). Test accounts: see frontend/README.md');
