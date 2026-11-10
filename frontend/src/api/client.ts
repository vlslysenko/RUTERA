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
  /** Seconds from the Retry-After header (429 auth.login.throttled), when the server sent one. */
  readonly retryAfter?: number;

  constructor(status: number, body: Partial<ApiErrorBody> | null, retryAfter?: number) {
    super(body?.message ?? body?.code ?? `HTTP ${status}`);
    this.name = 'ApiError';
    this.status = status;
    this.code = body?.code ?? defaultCode(status);
    this.details = body?.details;
    this.retryAfter = retryAfter;
  }
}

/** Retry-After as seconds: a number of seconds or an HTTP date; undefined when missing or unreadable. */
function retryAfterOf(res: Response): number | undefined {
  const value = res.headers.get('Retry-After');
  if (!value) return undefined;
  const seconds = Number(value);
  if (Number.isFinite(seconds)) return Math.max(0, seconds);
  const at = Date.parse(value);
  return Number.isNaN(at) ? undefined : Math.max(0, Math.round((at - Date.now()) / 1000));
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
    case 502:
    case 503:
    case 504:
      // The proxy or load balancer answered, the backend did not.
      return 'error.network';
    default:
      return 'error.internal';
  }
}

export type HttpMethod = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';

let unauthenticated: (() => void) | null = null;

/**
 * Called on a 401 from any request outside /auth/* (the session ended: 12 h after sign-in, 4 h idle or a server
 * restart), also from calls a screen makes directly rather than through a query or mutation. /auth/me and the sign-in
 * answer 401 on purpose and are left out, or asking the session again would loop. Set in src/app/queryClient.ts.
 */
export function setUnauthenticatedHandler(fn: (() => void) | null): void {
  unauthenticated = fn;
}

function sessionEnded(path: string, status: number): void {
  if (status === 401 && !path.startsWith('/auth/')) unauthenticated?.();
}

export interface RequestOptions {
  method?: HttpMethod;
  body?: unknown;
  query?: Record<string, string | number | boolean | undefined>;
  signal?: AbortSignal;
  /** Extra request headers, e.g. Idempotency-Key for requests replayed from the offline outbox. */
  headers?: Record<string, string>;
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

/**
 * The backend writes the XSRF-TOKEN cookie on every response and clears it on logout. When the cookie is missing
 * (fresh browser, after sign-out) any GET brings a new one; /auth/me is public and cheap.
 */
async function refreshCsrfToken(doFetch: Fetch): Promise<void> {
  try {
    await doFetch(`${API_BASE}/auth/me`, {
      method: 'GET',
      headers: { Accept: 'application/json' },
      credentials: 'same-origin',
    });
  } catch {
    // The real request below reports the network problem.
  }
}

export async function api<T>(path: string, opts: RequestOptions = {}, csrfRetried = false): Promise<T> {
  const method = opts.method ?? 'GET';
  const url = `${API_BASE}${path}${opts.query ? toQuery(opts.query) : ''}`;
  const headers: Record<string, string> = { Accept: 'application/json', ...opts.headers };
  // FormData (file uploads) goes as multipart: the browser sets Content-Type with the boundary itself.
  const multipart = opts.body instanceof FormData;
  if (opts.body !== undefined && !multipart) headers['Content-Type'] = 'application/json';

  const doFetch = await transport();
  if (method !== 'GET') {
    if (!csrfToken() && !USE_MOCK) await refreshCsrfToken(doFetch);
    const token = csrfToken();
    if (token) headers['X-XSRF-TOKEN'] = token;
  }

  let res: Response;
  try {
    res = await doFetch(url, {
      method,
      headers,
      body: multipart ? (opts.body as FormData) : opts.body !== undefined ? JSON.stringify(opts.body) : undefined,
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
  if (!res.ok) {
    const error = new ApiError(res.status, data as Partial<ApiErrorBody> | null, retryAfterOf(res));
    // Stale or rotated CSRF token (e.g. the first POST after sign-out): fetch a fresh one and retry once.
    if (error.status === 403 && error.code === 'auth.csrf_invalid' && method !== 'GET' && !csrfRetried) {
      await refreshCsrfToken(doFetch);
      return api<T>(path, opts, true);
    }
    sessionEnded(path, error.status);
    throw error;
  }
  return data as T;
}

/**
 * GET of a binary resource (file download) through the same transport, so it works with the mock too.
 * Returns the bytes and the file name from Content-Disposition.
 */
export async function apiDownload(path: string): Promise<{ blob: Blob; fileName: string | null }> {
  const doFetch = await transport();
  let res: Response;
  try {
    res = await doFetch(`${API_BASE}${path}`, { method: 'GET', credentials: 'same-origin' });
  } catch {
    throw new ApiError(0, { code: 'error.network' });
  }
  if (!res.ok) {
    let body: Partial<ApiErrorBody> | null = null;
    try {
      body = (await res.json()) as Partial<ApiErrorBody>;
    } catch {
      body = null;
    }
    sessionEnded(path, res.status);
    throw new ApiError(res.status, body);
  }
  const disposition = res.headers.get('Content-Disposition') ?? '';
  const encoded = /filename\*=UTF-8''([^;]+)/i.exec(disposition)?.[1];
  const plain = /filename="?([^";]+)"?/i.exec(disposition)?.[1];
  return { blob: await res.blob(), fileName: encoded ? decodeURIComponent(encoded) : (plain ?? null) };
}

/** Lets the browser save a blob under the given name. */
export function saveBlob(blob: Blob, fileName: string): void {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = fileName;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
