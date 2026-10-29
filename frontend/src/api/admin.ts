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
