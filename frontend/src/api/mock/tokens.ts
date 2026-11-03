import type { ApiCall, ApiToken, ApiTokenInput } from '@/api/tokens';
import type { MockReply } from './trips';

/*
 * Personal API tokens for the in-browser mock API, with the rules of ApiTokenService: name required (100), expiry in
 * the future, at most 10 active personal tokens per user, the token "rtr_" + 43 characters shown once, a made-up call
 * log. Each user also gets one app connected over OAuth (kind OAUTH, as after the consent page of the backend).
 */

const MAX_ACTIVE = 10;

interface StoredToken extends Omit<ApiToken, 'token' | 'active'> {
  calls: ApiCall[];
}

const tokens: StoredToken[] = [];

const active = (x: StoredToken) => !x.revokedAt && (!x.expiresAt || x.expiresAt > new Date().toISOString());
const view = (x: StoredToken, token: string | null = null): ApiToken => {
  const { calls: _c, ...rest } = x;
  return { ...rest, active: active(x), token };
};

function randomToken(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(32));
  return `rtr_${btoa(String.fromCharCode(...bytes)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')}`;
}

/** A few calls so that the log is not empty in the mock. */
function sampleCalls(createdAt: string): ApiCall[] {
  const t0 = new Date(createdAt).getTime();
  return [
    { at: new Date(t0 + 60_000).toISOString(), method: 'GET', path: '/api/v1/vehicles', status: 200, durationMs: 18, ip: '127.0.0.1' },
    { at: new Date(t0 + 120_000).toISOString(), method: 'GET', path: '/api/v1/trips', status: 200, durationMs: 31, ip: '127.0.0.1' },
    { at: new Date(t0 + 180_000).toISOString(), method: 'POST', path: '/api/v1/orders', status: 422, durationMs: 12, ip: '127.0.0.1' },
  ].reverse();
}

/** One connected app per user, added on the first look at the list. */
const connected = new Set<number>();
function connectApp(user: { id: number; name: string }) {
  if (connected.has(user.id)) return;
  connected.add(user.id);
  const createdAt = new Date(Date.now() - 3 * 86_400_000).toISOString();
  const calls = sampleCalls(createdAt);
  tokens.push({
    id: Math.max(0, ...tokens.map((x) => x.id)) + 1,
    kind: 'OAUTH',
    name: 'Claude',
    prefix: 'OAuth',
    user,
    createdAt,
    expiresAt: new Date(Date.now() + 27 * 86_400_000).toISOString(),
    lastUsedAt: calls[0].at,
    revokedAt: null,
    calls,
  });
}

export function listMine(user: { id: number; name: string }): MockReply {
  connectApp(user);
  const userId = user.id;
  return { status: 200, body: tokens.filter((x) => x.user.id === userId).sort((a, b) => b.id - a.id).map((x) => view(x)) };
}

export function listAll(): MockReply {
  return { status: 200, body: [...tokens].sort((a, b) => b.id - a.id).map((x) => view(x)) };
}

export function create(b: Partial<ApiTokenInput>, user: { id: number; name: string }, aiAccess: boolean): MockReply {
  if (!aiAccess) return { status: 403, body: { status: 403, code: 'api.ai_access_required', message: 'api.ai_access_required' } };
  const errors: Record<string, string> = {};
  const name = (b.name ?? '').trim();
  if (!name) errors.name = 'validation.required';
  else if (name.length > 100) errors.name = 'validation.too_long';
  else if (tokens.filter((x) => x.kind === 'PERSONAL' && x.user.id === user.id && !x.revokedAt).length >= MAX_ACTIVE) errors.name = 'api.token.limit';
  if (b.expiresAt && b.expiresAt <= new Date().toISOString()) errors.expiresAt = 'validation.invalid_value';
  if (Object.keys(errors).length) return { status: 422, body: { status: 422, code: 'error.validation', message: 'error.validation', details: errors } };
  const token = randomToken();
  const now = new Date().toISOString();
  const stored: StoredToken = {
    id: Math.max(0, ...tokens.map((x) => x.id)) + 1,
    kind: 'PERSONAL',
    name,
    prefix: token.slice(0, 12),
    user,
    createdAt: now,
    expiresAt: b.expiresAt ?? null,
    lastUsedAt: null,
    revokedAt: null,
    calls: [],
  };
  stored.calls = sampleCalls(now);
  stored.lastUsedAt = stored.calls[0].at;
  tokens.push(stored);
  return { status: 201, body: view(stored, token) };
}

export function revoke(id: string, userId: number | null): MockReply {
  const x = tokens.find((t) => t.id === Number(id) && (userId == null || t.user.id === userId));
  if (!x) return { status: 404, body: { status: 404, code: 'error.not_found', message: 'error.not_found' } };
  x.revokedAt ??= new Date().toISOString();
  return { status: 204 };
}

export function calls(id: string, userId: number, isAdmin: boolean, query: Record<string, string>): MockReply {
  const x = tokens.find((t) => t.id === Number(id) && (isAdmin || t.user.id === userId));
  if (!x) return { status: 404, body: { status: 404, code: 'error.not_found', message: 'error.not_found' } };
  const size = Number(query.size ?? 50);
  const from = Number(query.page ?? 0) * size;
  return { status: 200, body: { items: x.calls.slice(from, from + size), total: x.calls.length } };
}
