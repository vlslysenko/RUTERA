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
import type { Violation } from '@/api/hos';
import { addDays, buildViolations, daysFor, intervalsFor, ymd, type MockShiftDriver } from './hos';
import { isTrailer } from '@/api/fleet';
import type { ContractorInput, OrderInput, TripFromOrder, TripInput } from '@/api/trips';
import * as tripMock from './trips';
import type { AbsenceInput, DriverPaymentInput, PayRateInput } from '@/api/hr';
import * as hrMock from './hr';
import type { InvoiceInput, InvoicePaymentInput, ReminderSchedule } from '@/api/finance';
import * as financeMock from './finance';
import type { FuelAssignment, FuelCardInput, FuelTransactionInput } from '@/api/fuel';
import * as fuelMock from './fuel';
import type { CalcRequest, RouteRequest, TollTariffInput } from '@/api/calc';
import * as calcMock from './calc';
import * as routeMock from './route';
import type { CmrInput } from '@/api/cmr';
import * as cmrMock from './cmr';
import * as explanationMock from './explanations';
import type { OperatorFirmaCreate, OperatorFirmaInput } from '@/api/operator';
import type { ApiTokenInput } from '@/api/tokens';
import type { FirmaSettings, FirmaSettingsInput } from '@/api/admin';
import * as auditMock from './audit';
import * as operatorMock from './operator';
import * as tokenMock from './tokens';
import { decodeVin } from './vin';
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

/** Firma settings (GET / PUT /app/admin/firma); plan, limit and AI access are billing data, read-only. */
const firmaSettings: FirmaSettings = {
  id: MOCK_FIRMA.id, name: MOCK_FIRMA.name, shortCode: 'TRANSPOL', country: 'PL', vatId: 'PL8961000000',
  address: 'ul. Kolejowa 12\n55-080 Kąty Wrocławskie', email: 'biuro@transpol.example', lang: 'pl',
  timezone: MOCK_FIRMA.timezone ?? 'Europe/Warsaw', baseCurrency: 'PLN', plan: 'PRO', vehicleLimit: 30, aiAccess: true, driversSeePay: false,
  explanationLang: 'pl', explanationsAuto: true, explanationsDays: 28,
};
// Countries with an explanation template; elsewhere the PDF is in English (backend ExplanationTemplates).
const EXPLANATION_TEMPLATES: Record<string, string> = { PL: 'pl' };

function toUser(u: MockUser): User {
  const saved = readSettings()[String(u.id)] ?? {};
  return {
    id: u.id,
    login: u.login,
    name: fullName(u),
    role: u.role,
    // The operator (SUPERUSER) has no firma, like on the backend.
    firma: u.role === 'SUPERUSER' ? null : { ...MOCK_FIRMA, name: firmaSettings.name, timezone: firmaSettings.timezone, driversSeePay: firmaSettings.driversSeePay },
    language: saved.language ?? u.language,
    startScreen: saved.startScreen ?? null,
    privileges: ROLE_PRIVILEGES[u.role],
    driver: (() => {
      const own = ownDriverId(u);
      const d = own == null ? undefined : drivers.find((x) => x.id === own);
      return d ? { id: d.id, name: `${d.firstName} ${d.lastName}`.trim() } : null;
    })(),
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

/** Driver linked to the login (driver.user_id), for the driver app. */
function ownDriverId(u: MockUser): number | null {
  return drivers.find((d) => d.user?.id === u.id && !d.deleted)?.id ?? null;
}

/**
 * The privilege, or the driver app's own data: with driver_app.self/use a driver reads what belongs to the driver linked
 * to the login (proposed backend contract for the driver cabinet).
 */
function withPrivilegeOrSelf(
  key: string,
  isOwn: (req: MockRequest, ownDriver: number) => boolean,
  fn: (user: MockUser, req: MockRequest) => MockResponse | Promise<MockResponse>,
): Handler {
  return withAuth((u, req) => {
    if (u.role === 'SUPERUSER' || ROLE_PRIVILEGES[u.role].includes(key)) return fn(u, req);
    const own = ownDriverId(u);
    const self = own != null && ROLE_PRIVILEGES[u.role].includes('driver_app.self/use') && isOwn(req, own);
    return self ? fn(u, req) : error(403, 'auth.access_denied');
  });
}
const ownPathDriver = (req: MockRequest, own: number) => Number(req.params.id) === own;
const ownQueryDriver = (req: MockRequest, own: number) => Number(req.query.driverId) === own;

function passwordError(value: unknown): string | null {
  if (typeof value !== 'string' || value.length === 0) return 'validation.required';
  if (value.length < 10) return 'validation.too_short';
  if (value.length > 72) return 'validation.too_long';
  return null;
}

// Dictionaries are lazy chunks: only the requested language is loaded.
const locales = import.meta.glob<{ default: Dictionary }>('/src/i18n/locales/*.json');

// Like the backend: 20 failed sign-ins from one address in 15 min block it (429 with Retry-After in seconds).
const failedSignIns: number[] = [];
const THROTTLE_WINDOW_MS = 15 * 60_000;

route('POST', '/app/auth/login', ({ body }) => {
  const now = Date.now();
  while (failedSignIns.length && failedSignIns[0] <= now - THROTTLE_WINDOW_MS) failedSignIns.shift();
  if (failedSignIns.length >= 20) {
    const retryAfter = Math.ceil((failedSignIns[0] + THROTTLE_WINDOW_MS - now) / 1000);
    return { ...error(429, 'auth.login.throttled'), headers: { 'Retry-After': String(retryAfter) } };
  }
  const { login, password } = (body ?? {}) as Partial<{ login: string; password: string }>;
  const u = users.find((x) => x.login === String(login ?? '').trim().toLowerCase() && !x.deleted);
  if (!u || u.password !== password) {
    failedSignIns.push(now);
    return error(401, 'auth.login.invalid');
  }
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

route('GET', '/app/fleet/vin', withPrivilege('fleet.vehicle/view', (_u, req) => {
  const year = Number(req.query.year);
  return { status: 200, body: decodeVin(req.query.vin ?? '', Number.isInteger(year) && year > 0 ? year : null) };
}));

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

route('GET', '/app/drivers/:id', withPrivilegeOrSelf('driver.driver/view', ownPathDriver, (_u, req) => {
  const d = findDriver(req.params.id);
  return d ? { status: 200, body: toDriver(d, vehicles) } : error(404, 'error.not_found');
}));

/** Proposed DriverInput.userId: the login of the driver cabinet (role DRIVER, one driver per login); absent = unchanged. */
function driverUserError(b: DriverInput, selfId: number | null): string | null {
  if (b.userId == null) return null;
  const u = users.find((x) => x.id === b.userId && !x.deleted);
  if (!u || u.role !== 'DRIVER') return 'validation.invalid_value';
  return drivers.some((d) => d.user?.id === u.id && d.id !== selfId && !d.deleted) ? 'validation.invalid_value' : null;
}
function linkDriverUser(target: MockDriver, b: DriverInput): void {
  if (b.userId === undefined) return;
  const u = b.userId == null ? undefined : users.find((x) => x.id === b.userId);
  target.user = u ? { id: u.id, login: u.login } : null;
}

route('POST', '/app/drivers', withPrivilege('driver.driver/manage', (_u, { body }) => {
  const b = (body ?? {}) as DriverInput;
  const details = validateDriver(b, drivers, null);
  const userError = driverUserError(b, null);
  if (userError) details.userId = userError;
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
  linkDriverUser(created, b);
  drivers.push(created);
  return { status: 201, body: toDriver(created, vehicles) };
}));

route('PUT', '/app/drivers/:id', withPrivilege('driver.driver/manage', (_u, req) => {
  const target = findDriver(req.params.id);
  if (!target) return error(404, 'error.not_found');
  const b = (req.body ?? {}) as DriverInput;
  const details = validateDriver(b, drivers, target.id);
  const userError = driverUserError(b, target.id);
  if (userError) details.userId = userError;
  if (Object.keys(details).length > 0) return error(422, 'error.validation', details);
  applyDriverInput(target, b);
  linkDriverUser(target, b);
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

route('GET', '/app/drivers/:id/documents', withPrivilegeOrSelf('driver.driver/view', ownPathDriver, (_u, req) => {
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

// --- driver activity and violations (561/2006) --------------------------------------------------------

const shiftDrivers = (): MockShiftDriver[] =>
  drivers
    .filter((d) => !d.deleted && d.status === 'ACTIVE')
    .map((d) => {
      const v = vehicles.find((x) => !x.deleted && x.defaultDriverId === d.id);
      return { id: d.id, name: `${d.firstName} ${d.lastName}`, vehicle: v ? { id: v.id, plate: v.plate } : null };
    });

let violations: Violation[] | null = null;
/** Built on first use: 35 days back from today; older ones are partly reviewed already. */
function violationStore(): Violation[] {
  if (!violations) {
    const today = ymd(new Date());
    violations = buildViolations(shiftDrivers(), today, 35).map((v, i) => ({
      ...v,
      id: i + 1,
      ack:
        v.startAt < addDays(today, -10) && i % 3 !== 0
          ? { by: { id: 1, name: 'Anna Kowalska' }, at: addDays(v.startAt.slice(0, 10), 1) + 'T09:12:00Z', note: i % 2 ? 'Rozmowa z kierowcą' : null }
          : null,
    }));
  }
  return violations;
}

route('GET', '/app/tacho/activity', withPrivilegeOrSelf('tacho.hos/view', ownQueryDriver, (_u, { query }) => {
  const d = shiftDrivers().find((x) => String(x.id) === query.driverId);
  if (!d) return error(404, 'error.not_found');
  if (!query.from || !query.to || query.to < query.from) return error(422, 'error.validation', { to: 'validation.invalid_value' });
  const perDay = new Map<string, number>();
  for (const v of violationStore()) {
    if (v.driver.id !== d.id) continue;
    const day = ymd(new Date(v.startAt));
    perDay.set(day, (perDay.get(day) ?? 0) + 1);
  }
  return {
    status: 200,
    body: {
      driver: { id: d.id, name: d.name },
      timezone: Intl.DateTimeFormat().resolvedOptions().timeZone,
      days: daysFor(d, query.from, query.to, perDay),
      intervals: intervalsFor(d, query.from, query.to),
    },
  };
}));

route('GET', '/app/tacho/violations', withPrivilegeOrSelf('tacho.hos/view', ownQueryDriver, (_u, { query }) => {
  const page = Number(query.page ?? 0);
  const size = Number(query.size ?? 50);
  const items = violationStore()
    .filter((v) => !query.driverId || String(v.driver.id) === query.driverId)
    .filter((v) => !query.severity || v.severity === query.severity)
    .filter((v) => query.unacknowledged !== 'true' || !v.ack)
    .filter((v) => !query.from || ymd(new Date(v.startAt)) >= query.from)
    .filter((v) => !query.to || ymd(new Date(v.startAt)) <= query.to);
  return { status: 200, body: { items: items.slice(page * size, (page + 1) * size), total: items.length } };
}));

route('GET', '/app/tacho/violations/:id', withPrivilege('tacho.hos/view', (_u, req) => {
  const v = violationStore().find((x) => String(x.id) === req.params.id);
  return v ? { status: 200, body: { ...v, ...explanationMock.ofViolation(violationStore(), v.id) } } : error(404, 'error.not_found');
}));

// --- driver explanations (proposed contract, src/api/explanations.ts) ---------------------------------------

/** All explanations for the office (tacho.hos/view), the own ones for a driver in the cabinet, else none. */
function explanationScope(u: MockUser): number | null | false {
  if (u.role === 'SUPERUSER' || ROLE_PRIVILEGES[u.role].includes('tacho.hos/view')) return null;
  const own = ownDriverId(u);
  return own != null && ROLE_PRIVILEGES[u.role].includes('driver_app.self/use') ? own : false;
}
const scoped = (fn: (u: MockUser, req: MockRequest, own: number | null) => MockResponse): Handler =>
  withAuth((u, req) => {
    const s = explanationScope(u);
    return s === false ? error(403, 'auth.access_denied') : fn(u, req, s);
  });
const explanationLookup = {
  driver: (id: number) => {
    const d = drivers.find((x) => x.id === id && !x.deleted);
    return d ? { id: d.id, name: `${d.firstName} ${d.lastName}` } : undefined;
  },
  vehicle: (id: number) => {
    const v = vehicles.find((x) => x.id === id && !x.deleted);
    return v ? { id: v.id, plate: v.plate } : undefined;
  },
};
route('GET', '/app/explanations/reasons', scoped((_u, { query }) => explanationMock.reasons(query.ruleCode)));
route('GET', '/app/explanations', scoped((_u, { query }, own) => explanationMock.list(violationStore(), query, own)));
route('GET', '/app/explanations/:id/printout-note', scoped((_u, req) => explanationMock.printoutNote(violationStore(), req.params.id, req.query)));
route('GET', '/app/explanations/:id/pdf', scoped((_u, req, own) => explanationMock.pdf(violationStore(), req.params.id, own)));
route('GET', '/app/explanations/:id', scoped((_u, req, own) => explanationMock.get(violationStore(), req.params.id, own)));
route('POST', '/app/explanations', withPrivilege('tacho.violation/ack', (u, { body }) =>
  explanationMock.create(violationStore(), (body ?? {}) as Record<string, unknown>, explanationLookup, { id: u.id, name: fullName(u) })));
// Only the driver signs, in the cabinet.
route('POST', '/app/explanations/:id/sign', withAuth((u, req) => {
  const own = ownDriverId(u);
  return own == null ? error(403, 'auth.access_denied') : explanationMock.sign(violationStore(), req.params.id, (req.body ?? {}) as Record<string, string>, own);
}));
route('POST', '/app/explanations/:id/cancel', withPrivilege('tacho.violation/ack', (_u, req) => explanationMock.cancel(violationStore(), req.params.id)));
route('POST', '/app/explanations/:id/remind', withPrivilege('tacho.violation/ack', (_u, req) => explanationMock.remind(violationStore(), req.params.id)));

route('POST', '/app/tacho/violations/:id/ack', withPrivilege('tacho.violation/ack', (u, req) => {
  const v = violationStore().find((x) => String(x.id) === req.params.id);
  if (!v) return error(404, 'error.not_found');
  const note = ((req.body ?? {}) as { note?: string | null }).note?.trim() || null;
  v.ack = { by: { id: u.id, name: `${u.firstName} ${u.lastName}` }, at: new Date().toISOString(), note };
  return { status: 200, body: v };
}));

route('DELETE', '/app/tacho/violations/:id/ack', withPrivilege('tacho.violation/ack', (_u, req) => {
  const v = violationStore().find((x) => String(x.id) === req.params.id);
  if (!v) return error(404, 'error.not_found');
  v.ack = null;
  return { status: 200, body: v };
}));

// --- driver HR ----------------------------------------------------------------------------------

const hr: hrMock.HrLookup = { driverName };
/** The driver of /app/drivers/:id/... must exist; HR handlers get its numeric id. */
const forDriver = (key: string, fn: (u: MockUser, id: number, req: MockRequest) => MockResponse) =>
  withPrivilege(key, (u, req) => (liveDriver(Number(req.params.id)) ? fn(u, Number(req.params.id), req) : error(404, 'error.not_found')));
/** Read by the office, or by the driver app for the own driver. */
const forOwnDriver = (key: string, fn: (u: MockUser, id: number, req: MockRequest) => MockResponse) =>
  withPrivilegeOrSelf(key, ownPathDriver, (u, req) => (liveDriver(Number(req.params.id)) ? fn(u, Number(req.params.id), req) : error(404, 'error.not_found')));
const me = (u: MockUser) => ({ id: u.id, name: fullName(u) });

route('GET', '/app/drivers/:id/absences', forOwnDriver('driver.driver/view', (_u, id, { query }) => hrMock.listAbsences(id, query, hr)));
// The office enters approved absences; the driver asks for himself in the cabinet (REQUESTED).
route('POST', '/app/drivers/:id/absences', withPrivilegeOrSelf('driver.absence/manage', ownPathDriver, (u, req) =>
  hrMock.saveAbsence(Number(req.params.id), (req.body ?? {}) as AbsenceInput, null, hr, hasKey(u, 'driver.absence/manage') ? me(u) : null)));
route('PUT', '/app/drivers/:id/absences/:absenceId', forDriver('driver.absence/manage', (u, id, req) =>
  hrMock.saveAbsence(id, (req.body ?? {}) as AbsenceInput, req.params.absenceId, hr, me(u))));
route('DELETE', '/app/drivers/:id/absences/:absenceId', withPrivilegeOrSelf('driver.absence/manage', ownPathDriver, (u, req) =>
  hrMock.removeAbsence(Number(req.params.id), req.params.absenceId, !hasKey(u, 'driver.absence/manage'))));
route('PATCH', '/app/drivers/:id/absences/:absenceId', forDriver('driver.absence/manage', (u, id, req) =>
  hrMock.decideAbsence(id, req.params.absenceId, (req.body ?? {}) as { status?: string; note?: string | null }, hr, me(u))));
route('GET', '/app/absences', withPrivilege('driver.driver/view', (_u, { query }) => hrMock.firmAbsences(query, hr)));

route('GET', '/app/drivers/:id/days', forOwnDriver('driver.driver/view', (_u, id, { query }) => hrMock.month(id, query)));
route('PUT', '/app/drivers/:id/days/:day', forDriver('driver.driver/manage', (_u, id, req) =>
  hrMock.setDay(id, req.params.day, (req.body ?? {}) as { kind?: string; note?: string | null })));
route('DELETE', '/app/drivers/:id/days/:day', forDriver('driver.driver/manage', (_u, id, req) => hrMock.resetDay(id, req.params.day)));

route('GET', '/app/drivers/:id/pay-rates', forDriver('driver.payment/view', (_u, id) => hrMock.listRates(id)));
route('POST', '/app/drivers/:id/pay-rates', forDriver('driver.payment/manage', (_u, id, { body }) => hrMock.saveRate(id, (body ?? {}) as PayRateInput, null)));
route('PUT', '/app/drivers/:id/pay-rates/:rateId', forDriver('driver.payment/manage', (_u, id, req) =>
  hrMock.saveRate(id, (req.body ?? {}) as PayRateInput, req.params.rateId)));
route('DELETE', '/app/drivers/:id/pay-rates/:rateId', forDriver('driver.payment/manage', (_u, id, req) => hrMock.removeRate(id, req.params.rateId)));

// A driver sees its own payments only when the firma allows it (driversSeePay).
route('GET', '/app/drivers/:id/payments', withPrivilegeOrSelf('driver.payment/view', (req, own) => ownPathDriver(req, own) && firmaSettings.driversSeePay, (_u, req) =>
  liveDriver(Number(req.params.id)) ? hrMock.listPayments(Number(req.params.id), req.query, hr) : error(404, 'error.not_found')));
route('POST', '/app/drivers/:id/payments', forDriver('driver.payment/manage', (_u, id, { body }) =>
  hrMock.savePayment(id, (body ?? {}) as DriverPaymentInput, null, hr)));
route('PUT', '/app/drivers/:id/payments/:paymentId', forDriver('driver.payment/manage', (_u, id, req) =>
  hrMock.savePayment(id, (req.body ?? {}) as DriverPaymentInput, req.params.paymentId, hr)));
route('DELETE', '/app/drivers/:id/payments/:paymentId', forDriver('driver.payment/manage', (_u, id, req) => hrMock.removePayment(id, req.params.paymentId)));
route('GET', '/app/payments', withPrivilege('driver.payment/view', (_u, { query }) => hrMock.firmPayments(query, hr)));

// --- contractors, orders and trips --------------------------------------------------------------

const hasKey = (u: MockUser, key: string) => u.role === 'SUPERUSER' || ROLE_PRIVILEGES[u.role].includes(key);
const crew: tripMock.CrewLookup = {
  vehicle: (id) => {
    const v = vehicles.find((x) => x.id === id && !x.deleted);
    return v && { id: v.id, plate: v.plate, trailer: isTrailer(v.kind) };
  },
  driverName,
};
const contractorReader = (fn: (req: MockRequest) => MockResponse) =>
  withAuth((u, req) => (hasKey(u, 'trip.order/view') || hasKey(u, 'trip.contractor/manage') ? fn(req) : error(403, 'auth.access_denied')));

route('GET', '/app/contractors', contractorReader(({ query }) => tripMock.listContractors(query.q, query.role)));
route('GET', '/app/contractors/:id', contractorReader(({ params }) => tripMock.getContractor(params.id)));
route('POST', '/app/contractors', withPrivilege('trip.contractor/manage', (_u, { body }) => tripMock.saveContractor((body ?? {}) as ContractorInput, null)));
route('PUT', '/app/contractors/:id', withPrivilege('trip.contractor/manage', (_u, req) => tripMock.saveContractor((req.body ?? {}) as ContractorInput, req.params.id)));
route('DELETE', '/app/contractors/:id', withPrivilege('trip.contractor/manage', (_u, req) => tripMock.removeContractor(req.params.id)));

route('GET', '/app/orders', withPrivilege('trip.order/view', (_u, { query }) => tripMock.listOrders(query)));
route('GET', '/app/orders/:id', withPrivilege('trip.order/view', (_u, req) => tripMock.getOrder(req.params.id)));
route('POST', '/app/orders', withPrivilege('trip.order/manage', (_u, { body }) => tripMock.saveOrder((body ?? {}) as OrderInput, null)));
route('PUT', '/app/orders/:id', withPrivilege('trip.order/manage', (_u, req) => tripMock.saveOrder((req.body ?? {}) as OrderInput, req.params.id)));
route('DELETE', '/app/orders/:id', withPrivilege('trip.order/manage', (_u, req) => tripMock.removeOrder(req.params.id)));
route('POST', '/app/orders/:id/trip', withPrivilege('trip.order/manage', (u, req) =>
  hasKey(u, 'trip.trip/manage') ? tripMock.createTripForOrder(req.params.id, (req.body ?? {}) as TripFromOrder, crew) : error(403, 'auth.access_denied'),
));

route('GET', '/app/trips', withPrivilegeOrSelf('trip.trip/view', ownQueryDriver, (_u, { query }) => tripMock.listTrips(query, crew)));
route('GET', '/app/trips/:id', withPrivilegeOrSelf('trip.trip/view', (req, own) => tripMock.tripHasDriver(req.params.id, own), (_u, req) => tripMock.getTrip(req.params.id, crew)));
route('POST', '/app/trips', withPrivilege('trip.trip/manage', (_u, { body }) => tripMock.saveTrip((body ?? {}) as TripInput, null, crew)));
route('PUT', '/app/trips/:id', withPrivilege('trip.trip/manage', (_u, req) => tripMock.saveTrip((req.body ?? {}) as TripInput, req.params.id, crew)));
route('DELETE', '/app/trips/:id', withPrivilege('trip.trip/manage', (_u, req) => tripMock.removeTrip(req.params.id)));
const onTrip = (req: MockRequest, own: number) => tripMock.tripHasDriver(req.params.id, own);
route('GET', '/app/trips/:id/files', withPrivilegeOrSelf('trip.trip/view', onTrip, (_u, req) => tripMock.listTripFiles(req.params.id)));
route('GET', '/app/trips/:id/files/:fileId/content', withPrivilegeOrSelf('trip.trip/view', onTrip, (_u, req) => tripMock.tripFileContent(req.params.id, req.params.fileId)));
route('POST', '/app/trips/:id/files', withPrivilegeOrSelf('trip.cmr/upload', onTrip, (u, req) =>
  tripMock.uploadTripFile(req.params.id, req.body, { id: u.id, name: fullName(u) }, !hasKey(u, 'trip.cmr/upload') && !hasKey(u, 'trip.trip/manage'))));
route('DELETE', '/app/trips/:id/files/:fileId', withPrivilegeOrSelf('trip.trip/manage', onTrip, (u, req) =>
  tripMock.removeTripFile(req.params.id, req.params.fileId, u, !hasKey(u, 'trip.trip/manage'))));
route('PATCH', '/app/trips/:id/stops/:stopId', withPrivilegeOrSelf('trip.trip/manage', (req, own) => tripMock.tripHasDriver(req.params.id, own), (_u, req) =>
  tripMock.markStop(req.params.id, req.params.stopId, (req.body ?? {}) as { status?: string; actualAt?: string | null }),
));

// --- CMR consignment notes ---------------------------------------------------------------------------

const cmrLookup: cmrMock.CmrLookup = {
  crew,
  firma: { name: MOCK_FIRMA.name, address: 'ul. Kolejowa 12\n55-080 Kąty Wrocławskie', country: 'PL', vatId: 'PL8961000000' },
};
const cmrReader = (fn: (req: MockRequest) => MockResponse) =>
  withAuth((u, req) => (hasKey(u, 'trip.order/view') || hasKey(u, 'trip.cmr/manage') ? fn(req) : error(403, 'auth.access_denied')));

route('GET', '/app/cmr', cmrReader(({ query }) => cmrMock.listCmr(query, crew)));
route('GET', '/app/cmr/:id', cmrReader(({ params }) => cmrMock.getCmr(params.id, crew)));
route('GET', '/app/cmr/:id/pdf', cmrReader(({ params, query }) => cmrMock.cmrPdf(params.id, query.copies, crew)));
route('POST', '/app/cmr', withPrivilege('trip.cmr/manage', (_u, { body }) => cmrMock.saveCmr((body ?? {}) as CmrInput, null, crew)));
route('PUT', '/app/cmr/:id', withPrivilege('trip.cmr/manage', (_u, req) => cmrMock.saveCmr((req.body ?? {}) as CmrInput, req.params.id, crew)));
route('DELETE', '/app/cmr/:id', withPrivilege('trip.cmr/manage', (_u, req) => cmrMock.removeCmr(req.params.id)));
route('POST', '/app/cmr/from-order/:orderId', withPrivilege('trip.cmr/manage', (_u, req) => cmrMock.cmrFromOrder(req.params.orderId, cmrLookup)));
route('POST', '/app/cmr/from-trip/:tripId', withPrivilege('trip.cmr/manage', (_u, req) => cmrMock.cmrFromTrip(req.params.tripId, cmrLookup)));
route('POST', '/app/cmr/:id/issue', withPrivilege('trip.cmr/manage', (_u, req) => cmrMock.issueCmr(req.params.id, crew)));
route('POST', '/app/cmr/:id/cancel', withPrivilege('trip.cmr/manage', (_u, req) => cmrMock.cancelCmr(req.params.id, crew)));

// --- firma settings --------------------------------------------------------------------------------

route('GET', '/app/admin/firma', withPrivilege('admin.firma/manage', () => ({ status: 200, body: firmaSettings })));
route('PUT', '/app/admin/firma', withPrivilege('admin.firma/manage', (_u, { body }) => {
  const b = (body ?? {}) as Partial<FirmaSettingsInput>;
  const errors: Record<string, string> = {};
  const name = (b.name ?? '').trim();
  if (!name) errors.name = 'validation.required';
  else if (name.length > 200) errors.name = 'validation.too_long';
  if (b.email && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(b.email)) errors.email = 'validation.invalid_format';
  if (!b.lang || !isLanguageCode(b.lang)) errors.lang = b.lang ? 'validation.invalid_value' : 'validation.required';
  try {
    new Intl.DateTimeFormat('en', { timeZone: b.timezone ?? '' });
  } catch {
    errors.timezone = 'validation.invalid_value';
  }
  if (!/^[A-Z]{3}$/.test(b.baseCurrency ?? '')) errors.baseCurrency = 'validation.invalid_format';
  if (b.country != null && !/^[A-Z]{2}$/.test(b.country)) errors.country = 'validation.invalid_value';
  if (b.explanationsDays != null && (!Number.isInteger(b.explanationsDays) || b.explanationsDays < 1 || b.explanationsDays > 365)) errors.explanationsDays = 'validation.invalid_value';
  if (Object.keys(errors).length) return error(422, 'error.validation', errors);
  const country = b.country ?? firmaSettings.country;
  Object.assign(firmaSettings, {
    name, vatId: b.vatId ?? null, address: b.address ?? null, email: b.email ?? null, lang: b.lang, timezone: b.timezone,
    baseCurrency: b.baseCurrency, driversSeePay: b.driversSeePay ?? firmaSettings.driversSeePay,
    country, explanationLang: EXPLANATION_TEMPLATES[country] ?? 'en',
    explanationsAuto: b.explanationsAuto ?? firmaSettings.explanationsAuto, explanationsDays: b.explanationsDays ?? firmaSettings.explanationsDays,
  });
  return { status: 200, body: firmaSettings };
}));

// --- Web Push: the dev server has no service worker, so the mock answers like a server without VAPID keys ---------

route('GET', '/app/push/key', withAuth(() => error(404, 'push.disabled')));

// --- audit journal (admin.audit/view) ------------------------------------------------------------------

route('GET', '/app/admin/audit', withPrivilege('admin.audit/view', (u, req) => auditMock.list(req.query, u.role === 'SUPERUSER' ? null : MOCK_FIRMA.id)));

// --- operator console (SUPERUSER only) ------------------------------------------------------------------

operatorMock.initOperator({ ...firmaSettings, vehicleLimit: firmaSettings.vehicleLimit ?? 0 }, (f) => {
  if (f.id === MOCK_FIRMA.id) Object.assign(firmaSettings, { plan: f.plan, vehicleLimit: f.vehicleLimit, aiAccess: f.aiAccess });
});

function withOperator(fn: (user: MockUser, req: MockRequest) => MockResponse | Promise<MockResponse>): Handler {
  return withAuth((u, req) => (u.role === 'SUPERUSER' ? fn(u, req) : error(403, 'auth.access_denied')));
}

route('GET', '/app/operator/firmas', withOperator((_u, req) => operatorMock.list(req.query)));
route('POST', '/app/operator/firmas', withOperator((_u, { body }) =>
  operatorMock.create((body ?? {}) as Partial<OperatorFirmaCreate>, (login) => users.some((u) => u.login === login && !u.deleted)),
));
route('GET', '/app/operator/firmas/:id', withOperator((_u, req) => operatorMock.get(req.params.id)));
route('PUT', '/app/operator/firmas/:id', withOperator((_u, { params, body }) => operatorMock.update(params.id, (body ?? {}) as Partial<OperatorFirmaInput>)));

// --- personal API tokens (the mock firma has the AI access add-on) ---------------------------------------

route('GET', '/app/me/tokens', withPrivilege('profile.self/manage', (u) => tokenMock.listMine({ id: u.id, name: fullName(u) })));
route('POST', '/app/me/tokens', withPrivilege('profile.self/manage', (u, { body }) => tokenMock.create((body ?? {}) as ApiTokenInput, { id: u.id, name: fullName(u) }, true)));
route('DELETE', '/app/me/tokens/:id', withPrivilege('profile.self/manage', (u, req) => tokenMock.revoke(req.params.id, u.id)));
route('GET', '/app/me/tokens/:id/calls', withPrivilege('profile.self/manage', (u, req) => tokenMock.calls(req.params.id, u.id, hasKey(u, 'admin.api_token/manage'), req.query)));
route('GET', '/app/admin/tokens', withPrivilege('admin.api_token/manage', () => tokenMock.listAll()));
route('DELETE', '/app/admin/tokens/:id', withPrivilege('admin.api_token/manage', (_u, req) => tokenMock.revoke(req.params.id, null)));

// --- invoices, debts and reminders ---------------------------------------------------------------

route('GET', '/app/invoices', withPrivilege('finance.invoice/view', (_u, { query }) => financeMock.listInvoices(query)));
route('GET', '/app/invoices/:id', withPrivilege('finance.invoice/view', (_u, req) => financeMock.getInvoice(req.params.id)));
route('POST', '/app/invoices', withPrivilege('finance.invoice/manage', (_u, { body }) => financeMock.saveInvoice((body ?? {}) as InvoiceInput, null)));
route('PUT', '/app/invoices/:id', withPrivilege('finance.invoice/manage', (_u, req) => financeMock.saveInvoice((req.body ?? {}) as InvoiceInput, req.params.id)));
route('DELETE', '/app/invoices/:id', withPrivilege('finance.invoice/manage', (_u, req) => financeMock.removeInvoice(req.params.id)));
route('POST', '/app/invoices/:id/payments', withPrivilege('finance.payment/manage', (_u, req) =>
  financeMock.addPayment(req.params.id, (req.body ?? {}) as InvoicePaymentInput)));
route('DELETE', '/app/invoices/:id/payments/:paymentId', withPrivilege('finance.payment/manage', (_u, req) =>
  financeMock.removePayment(req.params.id, req.params.paymentId)));
route('GET', '/app/invoices/:id/reminders', withPrivilege('finance.invoice/view', (_u, req) => financeMock.listReminders(req.params.id)));
route('POST', '/app/invoices/:id/reminders', withPrivilege('finance.reminder/manage', (_u, req) => financeMock.sendReminder(req.params.id)));
route('GET', '/app/debts', withPrivilege('finance.invoice/view', (_u, { query }) => financeMock.debts(query)));
route('GET', '/app/reminders/schedule', withAuth((u) =>
  hasKey(u, 'finance.invoice/view') || hasKey(u, 'finance.reminder/manage') ? financeMock.getSchedule() : error(403, 'auth.access_denied')));
route('PUT', '/app/reminders/schedule', withPrivilege('finance.reminder/manage', (_u, { body }) =>
  financeMock.saveSchedule((body ?? {}) as Partial<ReminderSchedule>)));

// --- fuel cards and transactions -----------------------------------------------------------------

const fuelLookup = (u: MockUser): fuelMock.FuelLookup => ({
  vehicleByPlate: (plate) => {
    const v = vehicles.find((x) => !x.deleted && fuelMock.plateMatches(x.plate, plate));
    return v && { id: v.id, name: v.plate };
  },
  vehicle: (id) => {
    const v = vehicles.find((x) => x.id === id && !x.deleted);
    return v && { id: v.id, name: v.plate };
  },
  driverName,
  me: { id: u.id, name: fullName(u) },
});

route('POST', '/app/fuel/imports/preview', withPrivilege('fuel.import/manage', (_u, { body }) => fuelMock.preview(body)));
route('POST', '/app/fuel/imports', withPrivilege('fuel.import/manage', (u, { body }) => fuelMock.runImport(body, fuelLookup(u))));
route('GET', '/app/fuel/imports', withPrivilege('fuel.transaction/view', (_u, { query }) => fuelMock.listImports(query)));
route('GET', '/app/fuel/imports/:id', withPrivilege('fuel.transaction/view', (_u, req) => fuelMock.getImport(req.params.id)));
route('DELETE', '/app/fuel/imports/:id', withPrivilege('fuel.import/manage', (_u, req) => fuelMock.removeImport(req.params.id)));
route('GET', '/app/fuel/transactions', withPrivilege('fuel.transaction/view', (u, { query }) => fuelMock.listTransactions(query, fuelLookup(u))));
route('GET', '/app/fuel/transactions/:id', withPrivilege('fuel.transaction/view', (u, req) => fuelMock.getTransaction(req.params.id, fuelLookup(u))));
route('POST', '/app/fuel/transactions', withPrivilege('fuel.import/manage', (u, { body }) =>
  fuelMock.saveTransaction((body ?? {}) as FuelTransactionInput, null, fuelLookup(u))));
route('PUT', '/app/fuel/transactions/:id', withPrivilege('fuel.import/manage', (u, req) =>
  fuelMock.saveTransaction((req.body ?? {}) as FuelTransactionInput, req.params.id, fuelLookup(u))));
route('PATCH', '/app/fuel/transactions/:id', withPrivilege('fuel.import/manage', (u, req) =>
  fuelMock.assign(req.params.id, (req.body ?? {}) as FuelAssignment, fuelLookup(u))));
route('DELETE', '/app/fuel/transactions/:id', withPrivilege('fuel.import/manage', (_u, req) => fuelMock.removeTransaction(req.params.id)));
route('GET', '/app/fuel/cards', withPrivilege('fuel.transaction/view', (u) => fuelMock.listCards(fuelLookup(u))));
route('GET', '/app/fuel/cards/:id', withPrivilege('fuel.transaction/view', (u, req) => fuelMock.getCard(req.params.id, fuelLookup(u))));
route('POST', '/app/fuel/cards', withPrivilege('fuel.import/manage', (u, { body }) => fuelMock.saveCard((body ?? {}) as FuelCardInput, null, fuelLookup(u))));
route('PUT', '/app/fuel/cards/:id', withPrivilege('fuel.import/manage', (u, req) =>
  fuelMock.saveCard((req.body ?? {}) as FuelCardInput, req.params.id, fuelLookup(u))));
route('DELETE', '/app/fuel/cards/:id', withPrivilege('fuel.import/manage', (_u, req) => fuelMock.removeCard(req.params.id)));

// --- trip calculator and toll tariffs ------------------------------------------------------------

const calcLookup = (u: MockUser): calcMock.CalcLookup => ({
  vehicle: (id) => {
    const v = vehicles.find((x) => x.id === id && !x.deleted);
    return v && { id: v.id, avgL100km: v.avgL100km, euroClass: v.euroClass, axles: v.axles, grossWeightKg: v.grossWeightKg };
  },
  driverExists,
  payRate: (driverId, day) => hrMock.payRateFor(driverId, day),
  dieselPrice: () => fuelMock.avgDieselPrice(),
  canSeePay: hasKey(u, 'driver.payment/view'),
  firmaCountry: 'PL',
});

route('POST', '/app/calc/trip', withPrivilege('trip.trip/view', (u, { body }) => calcMock.plan((body ?? {}) as CalcRequest, calcLookup(u))));
route('GET', '/app/calc/places', withPrivilege('trip.trip/view', (_u, { query }) => routeMock.places(query.q, Number(query.limit) || 5)));
route('POST', '/app/calc/route', withPrivilege('trip.trip/view', (_u, { body }) => routeMock.route((body ?? {}) as Partial<RouteRequest>)));
route('GET', '/app/calc/fuel-prices', withPrivilege('trip.trip/view', () => ({ status: 200, body: calcMock.listFuelPrices() })));
route('GET', '/app/calc/toll-tariffs', withPrivilege('trip.trip/view', () => calcMock.listTolls()));
route('POST', '/app/calc/toll-tariffs', withPrivilege('trip.trip/manage', (_u, { body }) => calcMock.saveToll((body ?? {}) as TollTariffInput, null)));
route('PUT', '/app/calc/toll-tariffs/:id', withPrivilege('trip.trip/manage', (_u, req) => calcMock.saveToll((req.body ?? {}) as TollTariffInput, req.params.id)));
route('DELETE', '/app/calc/toll-tariffs/:id', withPrivilege('trip.trip/manage', (_u, req) => calcMock.removeToll(req.params.id)));

// --- user administration -------------------------------------------------------------------------

const LOGIN_RE = /^[\p{L}\p{N}._@+-]{3,254}$/u;

function findUser(id: string): MockUser | undefined {
  return users.find((x) => String(x.id) === id && !x.deleted);
}

route('GET', '/app/admin/users', withPrivilege('admin.user/view', () => ({
  status: 200,
  body: users
    .filter((u) => !u.deleted && u.role !== 'SUPERUSER')
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
    headers: { 'Content-Type': 'application/json', ...r.headers },
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
