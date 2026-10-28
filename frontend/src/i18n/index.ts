import i18n, { type BackendModule, type ReadCallback } from 'i18next';
import { initReactI18next } from 'react-i18next';
import { fetchDictionary } from '@/api/i18n';
import en from './locales/en.json';
import { DEFAULT_LANGUAGE, isLanguageCode, LANGUAGE_CODES, type LanguageCode } from './languages';

/*
 * Same mechanism as the backend: flat keys (<module>.<topic>.<name>), one dictionary per language,
 * served by GET /app/i18n/{lang}. English is bundled as the fallback; the other 25 languages are loaded on demand.
 */

export const LANGUAGE_STORAGE_KEY = 'rutera.language';

export function detectLanguage(): LanguageCode {
  try {
    const saved = localStorage.getItem(LANGUAGE_STORAGE_KEY);
    if (isLanguageCode(saved)) return saved;
  } catch {
    // storage unavailable (private mode) - fall through
  }
  for (const tag of navigator.languages ?? [navigator.language]) {
    const code = tag.toLowerCase().split('-')[0];
    if (isLanguageCode(code)) return code;
  }
  return DEFAULT_LANGUAGE;
}

const backend: BackendModule = {
  type: 'backend',
  init() {},
  read(language: string, _namespace: string, callback: ReadCallback) {
    fetchDictionary(language)
      .then((dict) => callback(null, dict))
      .catch((e: unknown) => callback(e instanceof Error ? e : new Error(String(e)), null));
  },
};

void i18n
  .use(backend)
  .use(initReactI18next)
  .init({
    lng: detectLanguage(),
    fallbackLng: DEFAULT_LANGUAGE,
    supportedLngs: [...LANGUAGE_CODES],
    load: 'currentOnly',
    resources: { en: { translation: en } },
    partialBundledLanguages: true,
    keySeparator: false,
    nsSeparator: false,
    returnEmptyString: false,
    interpolation: { escapeValue: false },
    react: { useSuspense: true },
  });

i18n.on('languageChanged', (lng) => {
  document.documentElement.lang = lng;
});

/** Changes the UI language and remembers it in this browser. Saving to the user profile is done by the caller. */
export async function setLanguage(code: LanguageCode): Promise<void> {
  try {
    localStorage.setItem(LANGUAGE_STORAGE_KEY, code);
  } catch {
    // ignore
  }
  await i18n.changeLanguage(code);
}

export function currentLanguage(): LanguageCode {
  const lng = i18n.resolvedLanguage ?? i18n.language;
  return isLanguageCode(lng) ? lng : DEFAULT_LANGUAGE;
}

export default i18n;
