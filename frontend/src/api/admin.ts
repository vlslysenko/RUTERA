import type { AdminUser, AdminUserCreate, AdminUserUpdate } from '@/app/types';
import { api } from './client';

/*
 * User administration (README.md, "User administration"); privileges admin.user/view and admin.user/manage.
 *   GET    /app/admin/users               -> AdminUser[] (own firma, deleted excluded)
 *   GET    /app/admin/users/{id}          -> AdminUser | 404
 *   POST   /app/admin/users               -> 201 AdminUser | 422 details per field
 *   PUT    /app/admin/users/{id}          -> AdminUser (full replacement) | 403 admin.user.self_forbidden | role_not_allowed
 *   PUT    /app/admin/users/{id}/password -> 204 (also lifts a lockout)
 *   POST   /app/admin/users/{id}/unlock   -> 204
 *   DELETE /app/admin/users/{id}          -> 204 (soft delete, login stays reserved)
 */
export const adminUsersApi = {
  list: () => api<AdminUser[]>('/admin/users'),
  get: (id: number | string) => api<AdminUser>(`/admin/users/${id}`),
  create: (body: AdminUserCreate) => api<AdminUser>('/admin/users', { method: 'POST', body }),
  update: (id: number | string, body: AdminUserUpdate) =>
    api<AdminUser>(`/admin/users/${id}`, { method: 'PUT', body }),
  resetPassword: (id: number | string, newPassword: string) =>
    api<void>(`/admin/users/${id}/password`, { method: 'PUT', body: { newPassword } }),
  unlock: (id: number | string) => api<void>(`/admin/users/${id}/unlock`, { method: 'POST' }),
  remove: (id: number | string) => api<void>(`/admin/users/${id}`, { method: 'DELETE' }),
};

export const ADMIN_USERS_KEY = ['admin', 'users'] as const;

/** Firma settings (admin.firma/manage); plan, vehicle limit and the AI access add-on belong to billing (read-only). */
export interface FirmaSettings {
  id: number;
  name: string;
  shortCode: string;
  /** ISO alpha-2 */
  country: string;
  vatId: string | null;
  address: string | null;
  email: string | null;
  /** Language of documents and e-mails of the firma. */
  lang: string;
  /** IANA zone the firma's days are cut in. */
  timezone: string;
  baseCurrency: string;
  plan: string;
  vehicleLimit: number | null;
  aiAccess: boolean;
  /** Language of the driver explanation PDF, from the country ("en" while the country has no template yet). */
  explanationLang?: string;
  /** The import asks drivers for explanations of new violations of the last explanationsDays days. */
  explanationsAuto?: boolean;
  explanationsDays?: number;
  /** Drivers see their own pay rates and payments in the cabinet. */
  driversSeePay: boolean;
}

/** Country (ISO-2) and the explanation settings are kept when left out (backend cd005a4). */
export type FirmaSettingsInput = Pick<FirmaSettings, 'name' | 'vatId' | 'address' | 'email' | 'lang' | 'timezone' | 'baseCurrency' | 'driversSeePay'> & {
  country?: string;
  explanationsAuto?: boolean;
  explanationsDays?: number;
};

export const firmaApi = {
  get: () => api<FirmaSettings>('/admin/firma'),
  update: (body: FirmaSettingsInput) => api<FirmaSettings>('/admin/firma', { method: 'PUT', body }),
};

export const FIRMA_KEY = ['admin', 'firma'] as const;
