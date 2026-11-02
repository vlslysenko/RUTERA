import { api } from './client';
import type { Page } from './trips';

/*
 * Personal API tokens for the public API /api/v1 and the MCP server /mcp (backend 0910122, README "Public API").
 * Own tokens with profile.self/manage; all tokens of the firma with admin.api_token/manage. Creating needs the firma's
 * AI access add-on (403 api.ai_access_required); at most 10 active tokens per user (422 name: api.token.limit).
 */

export interface ApiToken {
  id: number;
  name: string;
  /** First 12 characters ("rtr_" + 8), the rest is never stored. */
  prefix: string;
  user: { id: number; name: string };
  createdAt: string;
  expiresAt: string | null;
  lastUsedAt: string | null;
  revokedAt: string | null;
  /** Not revoked and not expired. */
  active: boolean;
  /** The whole token, only in the answer of the creation. */
  token: string | null;
}

export interface ApiTokenInput {
  name: string;
  /** ISO instant in the future, or null for a token without expiry. */
  expiresAt: string | null;
}

export interface ApiCall {
  at: string;
  method: string;
  path: string;
  status: number;
  durationMs: number;
  ip: string | null;
}

export const tokensApi = {
  mine: () => api<ApiToken[]>('/me/tokens'),
  create: (body: ApiTokenInput) => api<ApiToken>('/me/tokens', { method: 'POST', body }),
  revoke: (id: number) => api<void>(`/me/tokens/${id}`, { method: 'DELETE' }),
  calls: (id: number, page: number, size: number) => api<Page<ApiCall>>(`/me/tokens/${id}/calls`, { query: { page, size } }),
  all: () => api<ApiToken[]>('/admin/tokens'),
  revokeAny: (id: number) => api<void>(`/admin/tokens/${id}`, { method: 'DELETE' }),
};

export const TOKENS_KEY = ['tokens'] as const;

/** The OpenAPI document of the public API (public, same origin). */
export const OPENAPI_URL = '/api/v1/openapi.json';
