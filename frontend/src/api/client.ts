import type { ApiErrorBody } from '@/app/types';

/** Internal backend endpoints for the React client (Spring controllers under /app, session cookie + CSRF). */
export const API_BASE = '/app';

/** Mock API is on by default in dev and off in production builds; VITE_API_MOCK overrides both. */
export const USE_MOCK = (import.meta.env.VITE_API_MOCK ?? (import.meta.env.DEV ? 'true' : 'false')) === 'true';

export class ApiError extends Error {
  readonly status: number;
  /** i18n key, e.g. auth.login.invalid, error.not_found. Also set for errors without a body. */
  readonly code: string;
  readonly details?: Record<string, string>;

  constructor(status: number, body: Partial<ApiErrorBody> | null) {
    super(body?.message ?? body?.code ?? `HTTP ${status}`);
    this.name = 'ApiError';
    this.status = status;
    this.code = body?.code ?? defaultCode(status);
    this.details = body?.details;
  }
}

function defaultCode(status: number): string {
  switch (status) {
    case 0:
      return 'error.network';
    case 400:
      return 'error.bad_request';
    case 401:
      return 'auth.unauthenticated';
    case 403:
      return 'auth.access_denied';
    case 404:
      return 'error.not_found';
    case 422:
      return 'error.validation';
    default:
      return 'error.internal';
  }
}

export type HttpMethod = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';

export interface RequestOptions {
  method?: HttpMethod;
  body?: unknown;
  query?: Record<string, string | number | boolean | undefined>;
  signal?: AbortSignal;
}

/** Spring Security CookieCsrfTokenRepository: cookie XSRF-TOKEN -> header X-XSRF-TOKEN on state-changing requests. */
function csrfToken(): string | null {
  const m = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/);
  return m ? decodeURIComponent(m[1]) : null;
}

function toQuery(query: NonNullable<RequestOptions['query']>): string {
  const p = new URLSearchParams();
  for (const [k, v] of Object.entries(query)) {
    if (v !== undefined) p.set(k, String(v));
  }
  const s = p.toString();
  return s ? `?${s}` : '';
}

type Fetch = (input: string, init: RequestInit) => Promise<Response>;

let transportPromise: Promise<Fetch> | null = null;

/** The mock transport is a separate chunk, loaded only when USE_MOCK is on. */
function transport(): Promise<Fetch> {
  if (!transportPromise) {
    transportPromise = USE_MOCK
      ? import('./mock').then((m) => m.mockFetch)
      : Promise.resolve((input: string, init: RequestInit) => fetch(input, init));
  }
  return transportPromise;
}

export async function api<T>(path: string, opts: RequestOptions = {}): Promise<T> {
  const method = opts.method ?? 'GET';
  const url = `${API_BASE}${path}${opts.query ? toQuery(opts.query) : ''}`;
  const headers: Record<string, string> = { Accept: 'application/json' };
  if (opts.body !== undefined) headers['Content-Type'] = 'application/json';
  if (method !== 'GET') {
    const token = csrfToken();
    if (token) headers['X-XSRF-TOKEN'] = token;
  }

  const doFetch = await transport();
  let res: Response;
  try {
    res = await doFetch(url, {
      method,
      headers,
      body: opts.body !== undefined ? JSON.stringify(opts.body) : undefined,
      credentials: 'same-origin',
      signal: opts.signal,
    });
  } catch (e) {
    if (e instanceof DOMException && e.name === 'AbortError') throw e;
    throw new ApiError(0, { code: 'error.network' });
  }

  if (res.status === 204) return undefined as T;
  const text = await res.text();
  let data: unknown = null;
  if (text) {
    try {
      data = JSON.parse(text);
    } catch {
      data = null;
    }
  }
  if (!res.ok) throw new ApiError(res.status, data as Partial<ApiErrorBody> | null);
  return data as T;
}
