import type { LanguageCode } from '@/i18n/languages';

/** Roles as defined by the backend (role.* keys in messages.properties). */
export type Role =
  | 'SUPERUSER'
  | 'OWNER'
  | 'DISPATCHER'
  | 'LOGISTICIAN'
  | 'ACCOUNTANT'
  | 'DRIVER'
  | 'API'
  | 'CUSTOM';

/** Keys of the main screens; also the values a user can pick as the start screen. */
export type ScreenKey =
  | 'overview'
  | 'map'
  | 'trips'
  | 'fleet'
  | 'drivers'
  | 'tacho'
  | 'documents'
  | 'finance'
  | 'fuel'
  | 'reports';

export interface Firma {
  id: number;
  name: string;
}

export interface User {
  id: number;
  login: string;
  /** Display name, e.g. "Anna Kowalska". */
  name: string;
  role: Role;
  /** null for service accounts without a company (SUPERUSER). */
  firma: Firma | null;
  language: LanguageCode;
  /** null = default start screen for the role. */
  startScreen: ScreenKey | null;
  /** Effective privilege keys "module.entity/action"; empty for SUPERUSER, who bypasses checks. */
  privileges: string[];
}

export interface UserSettingsPatch {
  language?: LanguageCode;
  startScreen?: ScreenKey | null;
}

/** PUT /app/me/password */
export interface PasswordChange {
  currentPassword: string;
  newPassword: string;
}

/** Roles an administrator can assign; CUSTOM needs a custom role catalogue that does not exist yet. */
export const ASSIGNABLE_ROLES: readonly Role[] = ['OWNER', 'DISPATCHER', 'LOGISTICIAN', 'ACCOUNTANT', 'DRIVER'];

/** /app/admin/users */
export interface AdminUser {
  id: number;
  login: string;
  firstName: string;
  lastName: string | null;
  /** "Last First" as shown in lists. */
  name: string;
  role: Role;
  customRoleId: number | null;
  phone: string | null;
  language: LanguageCode;
  active: boolean;
  /** Temporarily locked after too many failed sign-ins. */
  locked: boolean;
  failedLogins: number;
  createdAt: string;
  updatedAt: string;
}

export interface AdminUserCreate {
  login: string;
  firstName: string;
  lastName?: string | null;
  role: Role;
  customRoleId?: number | null;
  phone?: string | null;
  /** Defaults to the firma language. */
  language?: LanguageCode | null;
  password: string;
}

/** Full replacement; login and password are changed by separate calls. */
export interface AdminUserUpdate {
  firstName: string;
  lastName: string | null;
  role: Role;
  customRoleId: number | null;
  phone: string | null;
  language: LanguageCode;
  active: boolean;
}

/** Error body returned by the backend for 4xx/5xx; code is an i18n key (error.*, auth.*). */
export interface ApiErrorBody {
  code: string;
  message?: string;
  details?: Record<string, string>;
}
