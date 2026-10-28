import type { User, UserSettingsPatch } from '@/app/types';
import { isLanguageCode } from '@/i18n/languages';
import type { Dictionary } from '@/api/i18n';
import { MOCK_FIRMA, MOCK_USERS, MOCK_VEHICLES, type MockUser } from './data';

/*
 * In-browser mock of the backend's /app endpoints. Enabled with VITE_API_MOCK=true (default in dev).
 * The session lives in sessionStorage, per-user settings in localStorage, everything else is static.
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

function route(method: string, path: string, handler: Handler): void {
  const keys: string[] = [];
  const source = path.replace(/:([A-Za-z]+)/g, (_m, k: string) => {
    keys.push(k);
    return '([^/]+)';
  });
  routes.push({ method, pattern: new RegExp(`^${source}$`), keys, handler });
}

const error = (status: number, code: string): MockResponse => ({ status, body: { code } });

function readSettings(): Record<string, UserSettingsPatch> {
  try {
    return JSON.parse(localStorage.getItem(SETTINGS_KEY) ?? '{}') as Record<string, UserSettingsPatch>;
  } catch {
    return {};
  }
}

function toUser(u: MockUser): User {
  const saved = readSettings()[String(u.id)] ?? {};
  return {
    id: u.id,
    login: u.login,
    name: u.name,
    role: u.role,
    firma: MOCK_FIRMA,
    language: saved.language ?? u.language,
    startScreen: saved.startScreen ?? null,
  };
}

function currentMockUser(): MockUser | null {
  const id = sessionStorage.getItem(SESSION_KEY);
  return id ? (MOCK_USERS.find((u) => String(u.id) === id) ?? null) : null;
}

function withAuth(fn: (user: User) => MockResponse | Promise<MockResponse>): Handler {
  return () => {
    const u = currentMockUser();
    return u ? fn(toUser(u)) : error(401, 'auth.unauthenticated');
  };
}

// Dictionaries are lazy chunks: only the requested language is loaded.
const locales = import.meta.glob<{ default: Dictionary }>('/src/i18n/locales/*.json');

route('POST', '/app/auth/login', ({ body }) => {
  const { login, password } = (body ?? {}) as Partial<{ login: string; password: string }>;
  const u = MOCK_USERS.find((x) => x.login === String(login ?? '').trim().toLowerCase());
  if (!u || u.password !== password) return error(401, 'auth.login.invalid');
  sessionStorage.setItem(SESSION_KEY, String(u.id));
  return { status: 200, body: toUser(u) };
});

route('POST', '/app/auth/logout', () => {
  sessionStorage.removeItem(SESSION_KEY);
  return { status: 204 };
});

route('GET', '/app/auth/me', withAuth((user) => ({ status: 200, body: user })));

route('PUT', '/app/me/settings', ({ body }) => {
  const u = currentMockUser();
  if (!u) return error(401, 'auth.unauthenticated');
  const patch = (body ?? {}) as UserSettingsPatch;
  if (patch.language !== undefined && !isLanguageCode(patch.language)) return error(422, 'error.validation');
  const all = readSettings();
  all[String(u.id)] = { ...all[String(u.id)], ...patch };
  localStorage.setItem(SETTINGS_KEY, JSON.stringify(all));
  return { status: 200, body: toUser(u) };
});

route('GET', '/app/i18n/:lang', async ({ params }) => {
  const loader = locales[`/src/i18n/locales/${params.lang}.json`];
  if (!loader) return error(404, 'error.not_found');
  const mod = await loader();
  return { status: 200, body: mod.default };
});

route('GET', '/app/fleet/vehicles', withAuth(() => ({ status: 200, body: MOCK_VEHICLES })));

route('GET', '/app/fleet/vehicles/:id', (req) =>
  withAuth(() => {
    const v = MOCK_VEHICLES.find((x) => String(x.id) === req.params.id);
    return v ? { status: 200, body: v } : error(404, 'error.not_found');
  })(req),
);

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
