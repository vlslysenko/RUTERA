import { api } from './client';
import type { Page } from './trips';

/*
 * Operator console of a cloud installation (backend 8f6708c, README "Operator console"): the paid options and the
 * state of every firma. Only for the role SUPERUSER (logins without a firma); everyone else gets 403
 * auth.access_denied. A firma that is not active or whose expiresAt has passed cannot sign in.
 */

export interface OperatorFirma {
  id: number;
  name: string;
  shortCode: string;
  country: string;
  plan: string;
  vehicleLimit: number;
  aiAccess: boolean;
  active: boolean;
  /** Test firma (demo data), shown with a chip. */
  test: boolean;
  /** Instant after which the firma cannot sign in; null = no end. */
  expiresAt: string | null;
  /** Active logins. */
  users: number;
  /** Vehicles not deleted (the limit is shown, not enforced). */
  vehicles: number;
  createdAt: string;
}

/** Body of PUT /app/operator/firmas/{id}: all operator fields. 422 per field: plan [A-Z0-9_]{1,32}, vehicleLimit 0..100000. */
export interface OperatorFirmaInput {
  plan: string;
  vehicleLimit: number;
  aiAccess: boolean;
  active: boolean;
  expiresAt: string | null;
}

/**
 * Body of POST /app/operator/firmas (backend 2d16fdc): a new firma and its first owner (role OWNER, the firma's
 * language). Omitted options take the backend defaults (lang en, UTC, EUR, STANDARD, 10 vehicles, no AI, not test).
 * 422 per field, nested with a dot: shortCode operator.firma.short_code_taken, owner.login admin.user.login_taken, ...
 */
export interface OperatorFirmaCreate {
  name: string;
  /** Letters, digits, - and _, 2..32, upper-cased by the backend. */
  shortCode: string;
  /** ISO 3166-1 alpha-2. */
  country: string;
  lang: string;
  timezone: string;
  baseCurrency: string;
  plan: string;
  vehicleLimit: number;
  aiAccess: boolean;
  test: boolean;
  expiresAt: string | null;
  owner: { login: string; firstName: string; lastName: string | null; password: string | null };
}

/** 201 of the creation; `password` is the generated owner password (16 characters) when none was given, shown once. */
export interface OperatorFirmaCreated {
  firma: OperatorFirma;
  ownerId: number;
  ownerLogin: string;
  password: string | null;
}

export const operatorApi = {
  create: (body: OperatorFirmaCreate) => api<OperatorFirmaCreated>('/operator/firmas', { method: 'POST', body }),
  list: (q: string, page: number, size: number) => api<Page<OperatorFirma>>('/operator/firmas', { query: { q: q || undefined, page, size } }),
  get: (id: number | string) => api<OperatorFirma>(`/operator/firmas/${id}`),
  update: (id: number | string, body: OperatorFirmaInput) => api<OperatorFirma>(`/operator/firmas/${id}`, { method: 'PUT', body }),
};

export const OPERATOR_KEY = ['operator', 'firmas'] as const;

export type FirmaState = 'ACTIVE' | 'BLOCKED' | 'EXPIRED';

/** Blocked by the operator, past its access end, or active. */
export function firmaState(f: Pick<OperatorFirma, 'active' | 'expiresAt'>, now = new Date()): FirmaState {
  if (!f.active) return 'BLOCKED';
  if (f.expiresAt && new Date(f.expiresAt) <= now) return 'EXPIRED';
  return 'ACTIVE';
}

/** "Access until" of a firma: the day of its expiresAt in UTC; the operator sets the end of that day (see expiresAtOf). */
export function expiresDay(expiresAt: string | null): string | null {
  return expiresAt ? expiresAt.slice(0, 10) : null;
}

/** "YYYY-MM-DD" (or "") from the form -> expiresAt: the end of that day in UTC, null = no end. */
export function expiresAtOf(day: string): string | null {
  return day ? `${day}T23:59:59Z` : null;
}
