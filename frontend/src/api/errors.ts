import { ApiError } from './client';

/** i18n key for any thrown value: ApiError.code, otherwise error.internal. */
export function errorCode(e: unknown): string {
  return e instanceof ApiError ? e.code : 'error.internal';
}
