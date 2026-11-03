import type { AuditChannel, AuditEntry } from '@/api/audit';
import type { MockReply } from './trips';

/*
 * Audit journal for the in-browser mock API with the filters of AuditQueryService: period (days in the browser's
 * zone here), user, module, channel, text in summary/action/entity; newest first; 422 for a bad channel or to < from.
 */

const HOUR = 3_600_000;

interface Seed {
  firmaId?: number;
  hoursAgo: number;
  userId: number | null;
  userLogin: string | null;
  channel: AuditChannel;
  module: string;
  action: string;
  entityType: string | null;
  entityId: number | null;
  summary: string;
  details?: Record<string, unknown>;
}

const SEEDS: Seed[] = [
  { hoursAgo: 0.3, userId: 1, userLogin: 'owner', channel: 'MCP', module: 'trip', action: 'order_created', entityType: 'order', entityId: 6, summary: 'Created order O-2026-0006 for Spedpol Sp. z o.o.', details: { client: 'Claude', tool: 'order_create', token: 'OAuth' } },
  { hoursAgo: 0.4, userId: 1, userLogin: 'owner', channel: 'MCP', module: 'fleet', action: 'vehicle_updated', entityType: 'vehicle', entityId: 3, summary: 'Updated vehicle DW 4471F', details: { client: 'Claude', tool: 'vehicle_update', changed: ['odometerKm'] } },
  { hoursAgo: 1.2, userId: 1, userLogin: 'owner', channel: 'API', module: 'finance', action: 'invoice_created', entityType: 'invoice', entityId: 12, summary: 'Created invoice FV/2026/11/003', details: { token: 'rtr_Ab12Cd34', path: 'POST /api/v1/invoices' } },
  { hoursAgo: 2, userId: 2, userLogin: 'dispatcher', channel: 'UI', module: 'trip', action: 'trip_updated', entityType: 'trip', entityId: 2, summary: 'Updated trip T-2026-0002' },
  { hoursAgo: 3, userId: 5, userLogin: 'driver', channel: 'UI', module: 'trip', action: 'stop_marked', entityType: 'trip', entityId: 2, summary: 'Stop 1 of trip T-2026-0002 marked DONE' },
  { hoursAgo: 5, userId: null, userLogin: null, channel: 'SYSTEM', module: 'finance', action: 'reminder_sent', entityType: 'invoice', entityId: 9, summary: 'Payment reminder sent for FV/2026/10/014' },
  { hoursAgo: 8, userId: 1, userLogin: 'owner', channel: 'UI', module: 'api', action: 'oauth_connected', entityType: 'api_token', entityId: 1, summary: 'Connected OAuth client Claude' },
  { hoursAgo: 9, userId: 1, userLogin: 'owner', channel: 'UI', module: 'auth', action: 'login', entityType: null, entityId: null, summary: 'Signed in' },
  { hoursAgo: 26, userId: 4, userLogin: 'accountant', channel: 'UI', module: 'auth', action: 'login_failed', entityType: null, entityId: null, summary: 'Failed sign-in (wrong password)' },
  { hoursAgo: 27, userId: 2, userLogin: 'dispatcher', channel: 'UI', module: 'tacho', action: 'file_imported', entityType: 'tacho_file', entityId: 4, summary: 'Imported tachograph file C_20261102_1200_KOWALSKI.ddd' },
  { hoursAgo: 30, userId: 1, userLogin: 'owner', channel: 'UI', module: 'admin', action: 'user_updated', entityType: 'user', entityId: 3, summary: 'Updated user logistician', details: { changed: ['role'] } },
  { hoursAgo: 50, userId: 3, userLogin: 'logistician', channel: 'UI', module: 'driver', action: 'absence_decided', entityType: 'absence', entityId: 2, summary: 'Approved vacation of Jan Kowalski 10.11-14.11' },
  { hoursAgo: 75, userId: 1, userLogin: 'owner', channel: 'API', module: 'fuel', action: 'fuel_imported', entityType: 'fuel_import', entityId: 1, summary: 'Imported 42 fuel transactions (DKV)', details: { token: 'rtr_Ab12Cd34' } },
  { hoursAgo: 100, userId: 1, userLogin: 'owner', channel: 'UI', module: 'admin', action: 'firma_settings_updated', entityType: 'firma', entityId: 1, summary: 'Updated company settings' },
  // Other firmas of the operator console (mock firmas 2 and 3).
  { firmaId: 2, hoursAgo: 1.5, userId: 21, userLogin: 'spedpol.owner', channel: 'MCP', module: 'fleet', action: 'vehicle_created', entityType: 'vehicle', entityId: 31, summary: 'Added vehicle PO 123AB', details: { client: 'Claude', tool: 'vehicle_create' } },
  { firmaId: 3, hoursAgo: 4, userId: 31, userLogin: 'nordfracht.disp', channel: 'UI', module: 'trip', action: 'trip_created', entityType: 'trip', entityId: 77, summary: 'Created trip T-2026-0041' },
  { firmaId: 2, hoursAgo: 20, userId: 6, userLogin: 'operator', channel: 'UI', module: 'operator', action: 'firma_options_changed', entityType: 'firma', entityId: 2, summary: 'Operator changed plan TRIAL, vehicle limit 5 -> plan TRIAL, vehicle limit 5, expires 2026-11-12' },
];

let entries: AuditEntry[] | null = null;

function all(): AuditEntry[] {
  entries ??= SEEDS.map((s, i) => ({
    id: SEEDS.length - i,
    at: new Date(Date.now() - s.hoursAgo * HOUR).toISOString(),
    firmaId: s.firmaId ?? 1,
    userId: s.userId,
    userLogin: s.userLogin,
    channel: s.channel,
    module: s.module,
    action: s.action,
    entityType: s.entityType,
    entityId: s.entityId,
    summary: s.summary,
    details: s.details ?? null,
    ip: s.channel === 'SYSTEM' ? null : '127.0.0.1',
    requestId: s.channel === 'SYSTEM' ? null : `req-${(SEEDS.length - i).toString(16).padStart(8, '0')}`,
  })).sort((a, b) => b.at.localeCompare(a.at));
  return entries;
}

const dayStart = (ymd: string) => new Date(`${ymd}T00:00:00`).getTime();

/** `scope`: the firma of the caller, or null for the operator (all firmas, or ?firmaId=). */
export function list(query: Record<string, string>, scope: number | null): MockReply {
  const firma = scope ?? (query.firmaId ? Number(query.firmaId) : null);
  const errors: Record<string, string> = {};
  if (query.channel && !['UI', 'API', 'MCP', 'SYSTEM'].includes(query.channel)) errors.channel = 'validation.invalid_value';
  if (query.from && query.to && query.to < query.from) errors.to = 'validation.invalid_value';
  const size = Number(query.size ?? 50);
  const page = Number(query.page ?? 0);
  if (size < 1 || size > 200) errors.size = 'validation.invalid_value';
  if (Object.keys(errors).length) return { status: 422, body: { status: 422, code: 'error.validation', message: 'error.validation', details: errors } };
  const q = (query.q ?? '').trim().toLowerCase();
  const found = all().filter(
    (e) =>
      (firma == null || e.firmaId === firma) &&
      (!query.from || new Date(e.at).getTime() >= dayStart(query.from)) &&
      (!query.to || new Date(e.at).getTime() < dayStart(query.to) + 86_400_000) &&
      (!query.userId || String(e.userId) === query.userId) &&
      (!query.module || e.module === query.module.toLowerCase()) &&
      (!query.channel || e.channel === query.channel) &&
      (!q || [e.summary, e.action, e.entityType ?? ''].some((x) => x.toLowerCase().includes(q))),
  );
  return { status: 200, body: { items: found.slice(page * size, page * size + size), total: found.length } };
}
