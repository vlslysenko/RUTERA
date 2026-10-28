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
  firma: Firma;
  language: LanguageCode;
  /** null = default start screen for the role. */
  startScreen: ScreenKey | null;
}

export interface UserSettingsPatch {
  language?: LanguageCode;
  startScreen?: ScreenKey | null;
}

/** Error body returned by the backend for 4xx/5xx; code is an i18n key (error.*, auth.*). */
export interface ApiErrorBody {
  code: string;
  message?: string;
  details?: Record<string, string>;
}
