import type { User, UserSettingsPatch } from '@/app/types';
import { api, ApiError } from './client';

export interface LoginRequest {
  login: string;
  password: string;
}

/*
 * Endpoint names agreed with the backend skeleton (all under /app):
 *   POST /app/auth/login    { login, password } -> 200 User | 401 { code: 'auth.login.invalid' | 'auth.login.locked' | ... }
 *   POST /app/auth/logout   -> 204
 *   GET  /app/auth/me       -> 200 User | 401 { code: 'auth.unauthenticated' }
 *   PUT  /app/me/settings   { language?, startScreen? } -> 200 User
 */
export const authApi = {
  login: (req: LoginRequest) => api<User>('/auth/login', { method: 'POST', body: req }),

  logout: () => api<void>('/auth/logout', { method: 'POST' }),

  /** Current user, or null when there is no session (401). Other errors propagate. */
  me: async (): Promise<User | null> => {
    try {
      return await api<User>('/auth/me');
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) return null;
      throw e;
    }
  },

  updateSettings: (patch: UserSettingsPatch) => api<User>('/me/settings', { method: 'PUT', body: patch }),
};
