import { api } from './client';
import type { Page } from './trips';

/*
 * Audit journal of the firma (backend 416af80, admin.audit/view): GET /app/admin/audit, newest first. Days of
 * from/to are cut in the firma's zone (inclusive; to < from -> 422 to). The channel tells where an action came from:
 * UI (a person in RUTERA), API / MCP (a script or an AI assistant with a token: "via AI"), SYSTEM (scheduled jobs).
 * summary is written by the backend in English; action is a code such as vehicle_created.
 */

export const AUDIT_CHANNELS = ['UI', 'API', 'MCP', 'SYSTEM'] as const;
export type AuditChannel = (typeof AUDIT_CHANNELS)[number];

/** Modules that write to the journal (lower-case, as stored). */
export const AUDIT_MODULES = ['fleet', 'driver', 'trip', 'finance', 'fuel', 'tacho', 'api', 'admin', 'auth', 'profile', 'calc', 'operator'] as const;

export interface AuditEntry {
  id: number;
  at: string;
  firmaId: number | null;
  userId: number | null;
  userLogin: string | null;
  channel: AuditChannel;
  module: string;
  action: string;
  entityType: string | null;
  entityId: number | null;
  summary: string;
  details: Record<string, unknown> | null;
  ip: string | null;
  requestId: string | null;
}

export interface AuditFilter {
  from?: string;
  to?: string;
  userId?: string;
  module?: string;
  channel?: string;
  q?: string;
  page: number;
  size: number;
}

export const auditApi = {
  list: (f: AuditFilter) =>
    api<Page<AuditEntry>>('/admin/audit', {
      query: { from: f.from, to: f.to, userId: f.userId, module: f.module, channel: f.channel, q: f.q, page: f.page, size: f.size },
    }),
};

export const AUDIT_KEY = ['admin', 'audit'] as const;

/** API and MCP: done by a script or an AI assistant with a token. */
export const viaAi = (channel: string) => channel === 'API' || channel === 'MCP';
