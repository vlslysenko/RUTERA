import { api } from './client';

/** Flat dictionary: { 'nav.overview': 'Overview', ... } - the same keys as messages_<lang>.properties on the backend. */
export type Dictionary = Record<string, string>;

/** GET /app/i18n/{lang} */
export const fetchDictionary = (lang: string) => api<Dictionary>(`/i18n/${encodeURIComponent(lang)}`);
